# Redis Professional Services Consultant — Technical Challenge

Solutions to the three exercises of the technical challenge, each in
its own directory with its own build, tests, and README covering the
design decisions, the Redis/Redis Enterprise concepts involved, and
step-by-step run instructions.

| Exercise | Directory | Stack | Summary |
|---|---|---|---|
| 1 — Building and Synchronizing Redis Databases | [`exercise1-sync/`](exercise1-sync/README.md) | Java, Quarkus, `quarkus-redis-client` | Writes 1-100 into `source-db` (a Sorted Set) and reads them back in reverse order from `replica-db`, a database kept in sync via Redis Enterprise's "Replica Of" feature. |
| 2 — Working with Redis REST API | [`exercise2-rest/`](exercise2-rest/README.md) | Java, Quarkus, MicroProfile REST Client | Uses the Redis Enterprise Cluster Manager REST API to create a database without modules, create three RBAC users with distinct roles, list them, and delete the database — all idempotently, safe to re-run. |
| 3 — Working with Semantic Routers | [`exercise3-router/`](exercise3-router/README.md) | Python, RedisVL | Classifies free-text queries into one of three topics (GenAI programming, science fiction, classical music) using vector embeddings and Redis Search, via RedisVL's `SemanticRouter`. |

Each exercise directory's README follows the same structure: relevant
theory/concepts first, then the design rationale, then build/test/run
instructions with the actual database endpoints used against this lab
environment's Redis Enterprise cluster.

## Running each exercise

Minimal commands to build and run each one; see each exercise's own
README for environment variables, tests, and troubleshooting.

### 1 — Building and Synchronizing Redis Databases

```bash
cd exercise1-sync
mvn -q package
java -jar target/quarkus-app/quarkus-run.jar
```

### 2 — Working with Redis REST API

```bash
cd exercise2-rest
mvn -q package
CLUSTER_ADMIN_EMAIL=admin@rl.org CLUSTER_ADMIN_PASSWORD=<cluster-admin-password> \
java -jar target/quarkus-app/quarkus-run.jar
```

Add `--no-delete-db` at the end to keep the created database around for
manual inspection instead of deleting it at the end of the run.

### 3 — Working with Semantic Routers

```bash
cd exercise3-router
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
export REDIS_URL="redis://<host>:<port>"
python -m exercise3_router.main
```

A specific query can be passed as an argument instead of running the
built-in demo queries, e.g. `python -m exercise3_router.main "What's a
good book about time travel?"`.
