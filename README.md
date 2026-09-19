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
