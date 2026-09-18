# Exercise 3 — Working with Semantic Routers

## 0. Scope

This exercise builds a semantic router: given a free-text query, it
returns the name of the best-matching topic out of three predefined
routes, using vector similarity search instead of keyword matching or
an LLM call. The three routes are:

1. GenAI programming topics
2. Science fiction entertainment
3. Classical music

Python was chosen over Java (used for Exercises 1-2) specifically
because the exercise text points to [RedisVL](https://github.com/redis/redis-vl-python),
which ships a `SemanticRouter` class implementing exactly this pattern
(embed a set of reference phrases per route, store them in a Redis
vector index, and classify a query by nearest-neighbor search) on top
of Redis's own Search and Query capability. No equivalent, similarly
complete library exists for Java; reimplementing index creation and
KNN queries by hand would add real complexity without changing what is
being demonstrated.

## 1. The database

RedisVL's `SemanticRouter` needs a Redis database with Search and
Query enabled (it creates and queries a vector index there); unlike
`source-db`/`replica-db` (pre-provisioned for Exercise 1), this
database does not exist yet and must be created first, the same way
`exercise2-db` was created in Exercise 2 - via the Cluster Manager REST
API, `POST /v1/bdbs`.

### Requirements from the exercise text

- Single shard: `"shards_count": 1` (the default already used for
  `exercise2-db"` — see `exercise2-rest/README.md` section 2).
- Single region: satisfied trivially, since this lab cluster is a
  single cluster, not a multi-region Active-Active (CRDB) setup — no
  extra field is needed to request that.
- Search and query enabled: `"module_list": [{"module_name": "search"}]`.
  Unlike Exercise 2, where the whole point was a database *without*
  modules, this one specifically needs the Search and Query module, so
  `module_list` is populated instead of omitted.
- Unauthenticated access, "for simplicity, as in the previous
  challenges": no `authentication_redis_pass` is set, matching
  `source-db`/`replica-db`/`exercise2-db`.

### Confirming the exact module name

`module_name` is looked up by exact string against the cluster's
installed modules, so it was confirmed against the cluster itself
rather than assumed, with:

```bash
curl -sk -u "$CLUSTER_ADMIN_EMAIL:$CLUSTER_ADMIN_PASSWORD" \
  "https://$CLUSTER_HOST:9443/v1/modules" \
  | python3 -c "
import json, sys
for m in json.load(sys.stdin):
    print(m.get('module_name'), m.get('display_name'), m.get('semantic_version'))
"
```

### Creating the database

```bash
curl -sk -u "$CLUSTER_ADMIN_EMAIL:$CLUSTER_ADMIN_PASSWORD" \
  -X POST "https://$CLUSTER_HOST:9443/v1/bdbs" \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "exercise3-router-db",
        "type": "redis",
        "memory_size": 104857600,
        "shards_count": 1,
        "module_list": [{"module_name": "search"}]
      }' \
  | python3 -m json.tool
```

The response's `uid` and, once provisioned, `dns_name_internal`/port
(same pattern as `source-db`/`replica-db`/`exercise2-db`) give the
`redis://` URL this program's `REDIS_URL` environment variable must
point to (see section 5).

### Deleting it afterward

```bash
curl -sk -u "$CLUSTER_ADMIN_EMAIL:$CLUSTER_ADMIN_PASSWORD" \
  -X DELETE "https://$CLUSTER_HOST:9443/v1/bdbs/<uid>"
```

There is no automated cleanup step for this exercise (unlike
`exercise2-rest`, which creates and deletes its own database every
run): this database is meant to persist across runs, since the vector
index and its reference embeddings live inside it and do not need to
be rebuilt each time (see section 4, "Re-running the program").

## 2. Why Python + RedisVL

`SemanticRouter` (from `redisvl.extensions.router`) already implements
the entire mechanism this exercise asks for:

- `Route(name, references, distance_threshold)` — a topic, its example
  phrases, and how strict a match has to be to count.
- Embeds every reference with a configured vectorizer and stores the
  vectors in a Redis Search index (schema, `FT.CREATE`, and writes are
  all handled internally).
- `router(query)` embeds the query the same way, runs a KNN search
  against that index, and returns the closest route's name if it is
  within that route's `distance_threshold`.

This is precisely "send requests to the best route" from the exercise
text, without hand-writing index management or search queries — RedisVL
is designed for exactly this, and reimplementing it manually in Java
would only add code, not correctness.

## 3. Route definitions

`exercise3_router/routes.py` defines the three required routes. Each
has multiple references — deliberately varied in phrasing (a direct
question, a recommendation request, a mention of specific well-known
names/works) — rather than one, so the route is represented by several
points in embedding space instead of a single narrow sentence:

| Route | Example references |
|---|---|
| GenAI programming topics | "How do I write a good prompt for a large language model?", "Explain how vector embeddings work in a RAG pipeline.", "I'm building an AI agent with tool calling, any tips?" |
| Science fiction entertainment | "What's the best sci-fi movie to watch this weekend?", "I loved the world-building in Dune, any similar books?", "Recommend a good cyberpunk or space opera novel." |
| Classical music | "What's a good Beethoven symphony for beginners?", "Can you recommend a Mozart piano concerto?", "Which orchestra has the best interpretation of Tchaikovsky?" |

`distance_threshold=0.5` is used for all three. RedisVL's own examples
for a handful of clearly distinct topics use thresholds in the
0.5-0.72 range (COSINE distance, range `(0, 2]`, lower is stricter).
These three topics are semantically far apart from each other, so a
moderately strict, mid-range value keeps genuine matches close to
their topic while still rejecting clearly unrelated input (see the
weather query in the demo set, section 4).

## 4. Architecture

- `routes.py` — the three `Route` definitions (no Redis or RedisVL
  runtime dependency beyond the `Route` type itself).
- `router_app.py` — `build_router()` constructs the `SemanticRouter`
  (routes + vectorizer + `REDIS_URL`); `classify(router, query)` is the
  only logic this project owns: it calls `router(query)` and returns
  its `.name`, or `NO_MATCH` if the router found nothing within
  threshold. This mirrors `exercise1-sync`/`exercise2-rest`: business
  logic (here, "what to print for a match/non-match") is kept behind a
  narrow interface, unit-testable without a live Redis connection or
  embedding model.
- `main.py` — builds the router, classifies either the command-line
  arguments or a small built-in demo set (one query per route, plus one
  deliberately unrelated query), and prints one route name per line.

### `redisvl` version

Pinned to `0.17.1`, the last release supporting Python 3.9 (`0.18.0`
and later require Python ≥3.10; the lab environment's Python is 3.9).
`SemanticRouter`/`Route` have the same API in `0.17.1`, so nothing in
this project's own code depends on the newer release.

### Vectorizer

`HFTextVectorizer` with `sentence-transformers/all-MiniLM-L6-v2`
(384-dimensional embeddings) — a local, offline model, so no API key
or external embedding service is required (the exercise's "no modules"
lab environment does not have one configured). It is smaller and
faster to download than RedisVL's own default
(`sentence-transformers/all-mpnet-base-v2`, ~420MB vs. ~90MB), and its
retrieval quality is more than sufficient to separate three topics as
distinct as these.

### Re-running the program

`build_router()` uses `overwrite=False`. `SemanticRouter` persists its
route config and reference vectors in the database created in section
1, so re-running the program reconnects to the same index instead of
rebuilding it (and briefly leaving it empty) on every run — the same
"safe to run repeatedly" property `exercise2-rest` added for its user
creation (see `exercise2-rest/README.md` section 7, "Re-running the
program: user creation is idempotent").

## 5. Configuration

`REDIS_URL` (environment variable, no default — same reasoning as
`exercise2-rest`'s cluster admin credentials: no database endpoint is
hard-coded into the code) must point at the database created in
section 1:

```bash
export REDIS_URL="redis://<host>:<port>"
```

## 6. Build, test, run

### Setup

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
```

### Tests (TDD)

`tests/test_classify.py` was written before `router_app.classify`
existed, using a small `FakeRouter` standing in for
`SemanticRouter` (a plain callable returning an object with a `.name`
attribute) — no live Redis connection or embedding model needed, same
principle as not mocking Jedis/the REST client directly in Exercises
1-2. `tests/test_routes.py` checks the route definitions themselves:
exactly the three required route names, each with more than one
reference and a valid `distance_threshold`.

```bash
pytest -v
```

### Run

```bash
export REDIS_URL="redis://<host>:<port>"
python -m exercise3_router.main
```

Expected output (one route name per line, matching the built-in demo
queries in `main.py`):

```
GenAI programming topics
Science fiction entertainment
Classical music
no matching route
```

A specific query can also be classified directly:

```bash
python -m exercise3_router.main "What's a good book about time travel?"
```
