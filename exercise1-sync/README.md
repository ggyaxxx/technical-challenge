# Exercise 1 — Building and Synchronizing Redis Databases

## 1. Create `source-db` and `replica-db` (Secure UI)

Steps to run manually on the Secure UI (`https://<cluster-ui>:8443`, login
`admin@rl.org` / the provided password):

1. **Create `source-db`**
   - Databases → New database
   - Name: `source-db`
   - Sharding: disabled / 1 shard ("single-sharded")
   - Memory limit: `2 GB`
   - Security: leave **no password** (disable "Enable Default User Password" / auth)
   - Create.
2. **Create `replica-db`**
   - Databases → New database
   - Name: `replica-db`
   - Sharding: 1 shard, Memory limit: `2 GB`, **no password** (same as above)
   - Under **Replication** / **Replica Of**: enable it and point it at
     `source-db` (select it from the cluster, or add it via its
     endpoint if it's on a different cluster).
   - Create.
3. Note down the **endpoint host:port** of both databases (Secure UI →
   database → Configuration tab). Use the IP address if the DNS name
   doesn't resolve from the load node / IDE.

> `replica-db` becomes read-only automatically (Redis enforces this on
> replicas) — that's expected and is exactly what Exercise 1 relies on:
> we write to `source-db`, we only ever read from `replica-db`.

## 2. Populate data with `memtier_benchmark` (load node)

SSH into the `load` node (via the bastion), then run something like:

```bash
memtier_benchmark \
  -s <source-db-host> -p <source-db-port> \
  --data-size=32 \
  -c 10 -t 4 -n 10000 \
  --ratio=1:1
```

- `-s` / `-p`: `source-db` endpoint (no `-a` flag needed — no password).
- `--data-size=32`: small values, just to generate load quickly.
- `-c 10 -t 4 -n 10000`: 10 clients × 4 threads × 10000 requests each.
- `--ratio=1:1`: even mix of SET/GET, the memtier_benchmark default workload.

Save the **exact command executed** into `/tmp/memtier_benchmark.txt` on
the load node, e.g.:

```bash
echo 'memtier_benchmark -s <source-db-host> -p <source-db-port> --data-size=32 -c 10 -t 4 -n 10000 --ratio=1:1' \
  > /tmp/memtier_benchmark.txt
```

This is independent from the Java program below: it's just there to
demonstrate load generation against `source-db` as requested by the
exercise.

## 3. The Java program

### Build

```bash
mvn -q package
```

Produces `target/exercise1-sync-jar-with-dependencies.jar` (self-contained,
no need for Maven/internet on the machine that runs it).

### Run

```bash
SOURCE_HOST=<source-db-host>   SOURCE_PORT=<source-db-port> \
REPLICA_HOST=<replica-db-host> REPLICA_PORT=<replica-db-port> \
java -jar target/exercise1-sync-jar-with-dependencies.jar
```

Expected output:

```
Connecting to source-db at <host>:<port> ...
Inserting values 1..100 into source-db (Sorted Set 'numbers') ...
Done.
Connecting to replica-db at <host>:<port> ...
Values read back from replica-db, in reverse order:
[100, 99, 98, ..., 2, 1]
```

### Design

- `NumberRepository` — the contract: insert a range, read it back reversed.
- `RedisSortedSetGateway` — a narrow port exposing only `ZADD`/`ZREVRANGE`,
  the two Redis commands the exercise actually needs. It exists so unit
  tests never have to mock Jedis' large `UnifiedJedis` class directly
  (best practice: don't mock types you don't own — and on some JVMs Jedis'
  class hierarchy simply can't be mocked at all).
- `JedisSortedSetGateway` — a 1:1 adapter from that port to real Jedis calls.
- `SortedSetNumberRepository` — the actual logic: `ZADD key <value> <value>`
  for each number (1..100), `ZREVRANGE key 0 -1` to read back reversed.
- `Exercise1App` — wires everything together against `source-db` and
  `replica-db`, using `SOURCE_HOST`/`SOURCE_PORT`/`REPLICA_HOST`/`REPLICA_PORT`
  env vars for the connection details.

### Tests (TDD)

`SortedSetNumberRepositoryTest` was written **before** the implementation:
it describes, with a mocked `RedisSortedSetGateway`, exactly which Redis
commands must be issued and how results must be parsed. Run with:

```bash
mvn -q test
```

## 4. Alternate Redis structures considered

The exercise says: insert 1-100, then read them back in reverse order.
Several Redis structures could do this; here's the comparison and why a
**Sorted Set** was chosen.

| Structure | How it would work | Pros | Cons |
|---|---|---|---|
| **Sorted Set (chosen)** | `ZADD numbers 1 "1"` … `ZADD numbers 100 "100"`; read with `ZREVRANGE numbers 0 -1` | Native, O(log N) insert, O(N) reverse read with **zero client-side logic** — Redis itself guarantees the order via the score. One key, no cleanup issues. | Slight overhead vs. a plain list (skip-list under the hood) — irrelevant at this scale. |
| **List** | `RPUSH numbers 1 2 ... 100`; read with `LRANGE numbers 0 -1` then reverse in the client (or `LPUSH` in ascending order so the list is already reversed) | Very simple, O(1) push. | "Reverse order" isn't a native read op on a List — you either reverse client-side or have to insert in reverse to begin with, which piggy-backs the ordering logic onto the *write* path instead of solving it where the requirement actually is (the *read*). |
| **Plain String keys** (`key:1` … `key:100`) | `SET key:1 1` … ; read via `SCAN`/`KEYS` + client-side numeric sort | Simplest possible write. | No relationship between the 100 keys in Redis's eyes: reading "in order" needs `SCAN` (unordered, requires cursoring) plus sorting entirely in the client. Also 100 keys instead of 1 — noisier keyspace, no atomicity across them. |
| **Hash** | `HSET numbers 1 1 2 2 ... ` | One key, O(1) field access. | Hashes are unordered by definition — Redis gives no ordering guarantee at all, so reversing would be 100% client-side logic on top of `HGETALL`. |

**Why Sorted Set won:** it's the only structure where "read in reverse
order" is a single, native, fully Redis-supported command
(`ZREVRANGE`/`ZREVRANGEBYSCORE`) rather than something the client has to
implement. That matches the rule we set for this challenge: every solution
should be fully supported by Redis itself, not by extra logic bolted on
around it.
