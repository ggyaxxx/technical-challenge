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

## Key concepts: how Semantic Routing works (theory)

This section documents the mechanism behind `SemanticRouter`, based on
reading its implementation (`redisvl.extensions.router.semantic`), not
just its public API — useful for explaining *why* it behaves the way
it does, not only *how* to call it.

### In plain terms, before the detail below

A computer cannot tell that "What's a good Beethoven symphony?" and
"Can you recommend a Mozart piano concerto?" are both about classical
music just by comparing words — they share none. What it *can* do is
turn each sentence into a list of numbers (384 of them, here) such
that sentences with similar meaning produce similar-looking lists of
numbers, and unrelated sentences produce very different ones. That
list of numbers is an **embedding**, and "similar-looking" is
measured with plain arithmetic (cosine distance, below) — no
understanding of English required by Redis itself.

Producing that list of numbers from a sentence requires a neural
network that has already been trained on huge amounts of text to do
exactly this — training one from scratch is far outside this
exercise's scope, so a ready-made one is downloaded instead:
`sentence-transformers/all-MiniLM-L6-v2`, a small (~90 MB) public
model from [Hugging Face](https://huggingface.co). "Downloading a
model" here means fetching that neural network's trained parameters
(pure data, cached locally after the first run) — separate from `pip
install`ing `redisvl`/`sentence-transformers` themselves, which only
installs the *code* able to run that network. It runs entirely on the
local CPU, so no external API key is needed (unlike RedisVL's
OpenAI/Cohere-backed vectorizer options).

Concretely, in this project: every one of the 18 reference sentences
in `routes.py` is turned into an embedding once, up front, and stored
in Redis (section 1); every incoming query is turned into an embedding
with that same model at query time, and Redis finds which stored
embeddings it is closest to. Everything below is the precise mechanics
of that "closest to" step.

### Embeddings and vector distance

An embedding is a fixed-length vector of floating-point numbers
produced by a model (here, `all-MiniLM-L6-v2`: 384 numbers) such that
texts with similar meaning end up as vectors that are close together
in that 384-dimensional space, and unrelated texts end up far apart.
"Close" is measured here with **cosine distance** (`0` = identical
direction, `2` = opposite direction; RedisVL's `distance_threshold` is
on this scale). This is what makes routing work on *meaning* rather
than shared keywords: "What's a good Beethoven symphony?" and "Can you
recommend a Mozart piano concerto?" share no words, but land close
together because both are about classical music.

### What building the router actually stores

`SemanticRouter.__init__` embeds every `reference` string from every
`Route` and writes each one as a separate record into a Redis index
(`FT.CREATE`), with three fields: `route_name` (tag), `reference`
(text, unused by routing itself), and a `vector` field — indexed with
a **`FLAT`** algorithm (brute-force, exact nearest-neighbor - not the
approximate `HNSW` used for large-scale search) and **`COSINE`**
distance. `FLAT` is appropriate here because a router's whole index is
only a few dozen reference vectors (18, in this project), not millions
of documents — exhaustive search is cheap enough that there is nothing
to trade accuracy for.

### What happens on each `router(query)` call

This is a single `FT.AGGREGATE` request built by RedisVL, not a
sequence of separate lookups:

1. **Embed the query** with the same vectorizer used at build time (a
   query and a reference are only comparable if produced by the same
   model).
2. **Vector range query**: fetch every *reference* whose distance to
   the query vector is below a threshold — since Redis's range query
   takes a single distance value, RedisVL uses the **largest**
   `distance_threshold` among all routes here as a first, coarse pass
   (all three routes use `0.5`, so this has no effect in this project,
   but would matter with routes having different thresholds).
3. **`GROUP BY route_name`, aggregating `distance`** across every
   reference of that route that passed step 2. The aggregation
   function defaults to **`avg`** (used here, unchanged) — meaning a
   route's overall distance is the *average* of its matching
   references' distances, not just its single closest reference. This
   is why several *varied* references per route (a question, a
   recommendation request, a specific work's name - see `routes.py`)
   are more robust than one: a route with one reference very close to
   the query but the rest far away scores worse under `avg` than a
   route where several references are moderately close.
4. **Filter by each route's *own* `distance_threshold`**: after
   aggregation, RedisVL applies a second filter,
   `(route_name == 'X' && distance < threshold_X) || ...`, one clause
   per route — this is what actually enforces each route's threshold;
   step 2's max-threshold range query was only ever a pre-filter to
   narrow down candidates before aggregation.
5. **Sort by distance ascending, keep the top 1** (`RoutingConfig.max_k`,
   default `1`) — the single best-scoring route that survived step 4.
6. If no route survived (every candidate's aggregated distance exceeded
   its own threshold, or step 2 found nothing at all within the widest
   threshold), the aggregation returns no rows, and `router(query)`
   returns a `RouteMatch(name=None, distance=None)` — never `None`
   itself, and never an exception. This is exactly the `NO_MATCH` case
   `classify()` handles (see `router_app.py`).

### Why this generalizes better than keyword matching

A keyword/regex router would need to anticipate every way a user might
phrase a request ("Beethoven", "symphony", "classical", "orchestra", …
for just one topic). Embedding-based routing instead only needs a
handful of *representative* phrasings per topic; anything semantically
similar - different wording, synonyms, even a different language the
embedding model was trained on - lands close to those same reference
vectors without being explicitly listed.

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
- RedisJSON enabled too (`{"module_name": "ReJSON"}`), even though the
  exercise text only asks for "search and query": `SemanticRouter`
  persists its own routing configuration with a raw `JSON.SET` call
  (`redisvl.extensions.router.semantic.SemanticRouter.__init__` calls
  `self._index.client.json().set(...)`), not only vector search. A
  database created with `search` alone fails at router construction
  with `redis.exceptions.ResponseError: unknown command 'JSON.SET'`.
  Every field in `module_list` also requires a non-empty JSON object,
  so each entry needs `"module_args": ""` even when there are no
  arguments to pass - omitting it fails with `invalid_schema`.
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
        "module_list": [
          {"module_name": "search", "module_args": ""},
          {"module_name": "ReJSON", "module_args": ""}
        ]
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

`SemanticRouter` is used with `HFTextVectorizer` — RedisVL's own
vectorizer class, not a replacement for it: `HFTextVectorizer` wraps
whichever `sentence-transformers` model it is given (RedisVL does not
bundle a fixed one). The model given here is
`sentence-transformers/all-MiniLM-L6-v2` (384-dimensional embeddings)
— a local, offline model, so no API key or external embedding service
is required (the exercise's "no modules" lab environment does not have
one configured). It is smaller and faster to download than the model
RedisVL's own examples typically default to
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
export REDIS_URL="redis://redis-14808.re-cluster1.ps-redislabs.org:14808"
```

The exact host/port were confirmed with the same endpoint-inspection
`curl` shown in section 1, and can change if the database is deleted
and recreated (Redis Enterprise can reassign a different proxy port to
a new database). If this URL stops responding, re-check the current
one:

```bash
curl -sk -u "$CLUSTER_ADMIN_EMAIL:$CLUSTER_ADMIN_PASSWORD" \
  "https://$CLUSTER_HOST:9443/v1/bdbs/21" \
  | python3 -c "
import json, sys
d = json.load(sys.stdin)
for e in d.get('endpoints', []):
    print('dns_name:', e.get('dns_name'), 'port:', e.get('port'))
"
```

If the hostname does not resolve from the machine running this
program, the exercise text explicitly allows falling back to the raw
IP address from the same `endpoints[].addr` field, e.g.
`redis://172.16.22.23:14808`.

## 6. Build, test, run

### Setup

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
```

#### Setup on a Python 3.9 machine without `sudo`

The lab VM has Python 3.9 without the Debian `python3-venv` package
(needed for `ensurepip`), and no `sudo` to install it. Two independent
workarounds combine to make the block above work anyway:

- **`redisvl` is pinned to `0.17.1`** in `requirements.txt` — the last
  release supporting Python 3.9 (`0.18.0`+ requires ≥3.10); its
  `SemanticRouter`/`Route` API is identical to the one used here.
- **`venv` created without `pip`, then `pip` bootstrapped manually**,
  bypassing the missing `python3-venv` package entirely:

```bash
python3 -m venv --without-pip .venv
curl -sS https://bootstrap.pypa.io/pip/3.9/get-pip.py -o /tmp/get-pip.py
.venv/bin/python3 /tmp/get-pip.py
. .venv/bin/activate
pip install --upgrade pip -q
# CPU-only torch first, to avoid pulling several GB of unused CUDA
# packages that sentence-transformers would otherwise resolve to.
pip install torch --index-url https://download.pytorch.org/whl/cpu -q
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
