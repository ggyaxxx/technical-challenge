# Exercise 1 — Building and Synchronizing Redis Databases

## 0. Key concepts (theory)

This section explains the Redis Enterprise concepts behind every choice
made below — useful to justify each step out loud (e.g. in an interview).

### What is a "database" (BDB) in Redis Enterprise?

What the Secure UI calls a "database" is internally a **BDB** (Basic
Database). It is **not** a whole dedicated server: it's a logical entity
that the cluster (the 3 nodes `re-n1`/`re-n2`/`re-n3`) manages and assigns
resources to — memory, one or more Redis processes ("shards"), a network
endpoint, persistence/HA settings, etc. `source-db` and `replica-db` are
two independent BDBs, each with its own endpoint, each backed by its own
Redis process(es), even though they run on shared cluster hardware.

### Shards / "single-sharded"

A **shard** is a single Redis process holding a portion (or, with no
sharding, *all*) of a BDB's data. When a BDB has multiple shards, Redis
Enterprise automatically partitions the keyspace across them (like a
Redis Cluster, but managed for you). Both `source-db` and `replica-db`
were created with **sharding disabled → 1 shard**: all data lives on a
single Redis process, exactly what "single-sharded" requires — no
partitioning, the simplest possible topology.

### Memory limit & eviction

`Memory limit (GB): 2` is the hard cap on RAM that BDB can use. The
"Memory eviction" feature tag means an eviction policy is active
(default `volatile-lru`): if memory fills up, Redis would first discard
keys **that have a TTL set**, least-recently-used first. None of the keys
in this exercise have a TTL, so eviction never actually triggers — but
it's worth knowing what the setting does.

### Access method: Unauthenticated access

No `AUTH` is required to connect (no password) — convenient for this
exercise, but something that would never be acceptable in production
(where password/ACL/TLS would be used instead).

### `source-db` vs `replica-db`: what "Replica Of" actually does

This is the core mechanism of the exercise:

- `source-db` is the primary: the Java program writes to it (`ZADD`), and
  `memtier_benchmark` generates load against it.
- `replica-db` is a **separate, distinct BDB** that has the **"Replica
  Of" feature enabled, pointing at `source-db`**. This makes `replica-db`
  behave like a **Redis replication client**: it connects to `source-db`
  using Redis's own native replication protocol (the same `SYNC`/`PSYNC`
  mechanism a classic Redis OSS replica uses), receives an initial RDB
  snapshot, then keeps streaming every subsequent write command in
  real time.
- Because of this, `replica-db` **automatically becomes read-only** —
  Redis refuses writes on a database that is "Replica Of" another one,
  exactly like a master/replica pair in vanilla Redis.

**Important distinction** (easy to confuse):
- The **"High Availability → Replication"** setting shown in the DB form
  (left as `Off` here) is a *different* concept: it's about a single
  BDB's *internal* HA (a master shard + a replica shard for automatic
  failover if a node dies). We didn't need it for this exercise.
- **"Replica Of"** links **two distinct BDBs** together (even across
  clusters, or even from a non-Enterprise Redis) purely for
  cross-database synchronization. This is the feature Exercise 1 asks for.

Real-world use cases for "Replica Of" worth mentioning: zero-downtime
migration between clusters/environments, active-passive disaster
recovery, geo-distributed read scaling, or keeping a test environment's
dataset continuously mirrored from production.

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

### What is `memtier_benchmark`?

It's Redis's own open-source load-generation/benchmarking tool. It opens
many concurrent client connections to a Redis (or Memcached) instance and
fires a configurable mix of commands (by default SET/GET) at it, then
reports throughput and latency statistics. Here it's used purely to
**populate real data** into `source-db`, as the exercise requires — the
performance numbers themselves aren't the point.

### Command actually run, and how to reach it

Access path: bastion (`term`/`***REDACTED***`) → `su` → `su labuser` → `ssh load`.
Then, on the `load` node:

```bash
memtier_benchmark -s redis-10649.re-cluster1.ps-redislabs.org -p 10649 \
  --data-size=32 -c 10 -t 4 -n 10000 --ratio=1:1 \
  --key-minimum=1 --key-maximum=1000000
```

Save the **exact command executed** into `/tmp/memtier_benchmark.txt` on
the load node (this is a hard requirement of the exercise):

```bash
echo 'memtier_benchmark -s redis-10649.re-cluster1.ps-redislabs.org -p 10649 --data-size=32 -c 10 -t 4 -n 10000 --ratio=1:1 --key-minimum=1 --key-maximum=1000000' \
  > /tmp/memtier_benchmark.txt
```

To make the run resilient to the lab's web terminal dropping the
connection mid-command, both steps were combined and detached from the
controlling terminal in one shot:

```bash
echo 'memtier_benchmark ...' > /tmp/memtier_benchmark.txt \
  && nohup memtier_benchmark ... > /tmp/memtier_output.log 2>&1 & disown
```

- `nohup` makes the process ignore `SIGHUP`, so it survives even if the
  SSH/websocket session dies right after launch.
- `> /tmp/memtier_output.log 2>&1` captures both stdout and stderr to a
  file that can be read after reconnecting.
- `& disown` backgrounds the process and detaches it from the shell's job
  table.

### Every flag, explained

| Flag | Meaning | Why this value |
|---|---|---|
| `-s <host>` (`--server`) | Hostname/IP of the target Redis | `source-db`'s endpoint — this is what directs the load specifically at `source-db`, satisfying "populate data **into source-db**" |
| `-p <port>` (`--port`) | Port of the target Redis | The port Redis Enterprise assigned to `source-db` (each BDB on the same node IP gets a distinct port) |
| `--data-size=32` | Size in bytes of the **value** written by each SET | Small, realistic payload — just needs to generate real data quickly, no size requirement in the exercise |
| `-c 10` (`--clients`) | Number of client connections **per thread** | 10 connections per thread |
| `-t 4` (`--threads`) | Number of internal worker threads, each with its own event loop | 4 threads × 10 clients = **40 concurrent connections** total |
| `-n 10000` (`--requests`) | Requests **per client** before it stops | 40 clients × 10,000 = **400,000 total requests** |
| `--ratio=1:1` | SET:GET ratio of generated commands | 1:1 → roughly half of the 400,000 requests are real **SET** commands, i.e. actual writes — this is the part that satisfies "populate *data*" (not just reads against empty keys) |
| `--key-minimum=1` / `--key-maximum=1000000` | Range of numeric IDs used to build key names (`memtier-<id>`) | Explicitly widened after noticing the *default* range on this lab's `memtier_benchmark` build behaved as if it were much smaller than the officially documented default (10,000,000) — see the note below |
| *(omitted)* `-a` (`--authenticate`) | Password for `AUTH` | Deliberately omitted — `source-db` has no password |

### Reading the `memtier_benchmark` report: what the percentiles mean

A typical run prints a table like this:

```
ALL STATS
============================================================================================================================
Type         Ops/sec     Hits/sec   Misses/sec    Avg. Latency     p50 Latency     p99 Latency   p99.9 Latency       KB/sec
----------------------------------------------------------------------------------------------------------------------------
Sets        13783.64          ---          ---         1.44489         1.14300         6.20700        14.14300      1061.85
Gets        13783.64        11.03     13772.61         1.43992         1.14300         6.27100        14.46300       537.29
Waits           0.00          ---          ---             ---             ---             ---             ---          ---
Totals      27567.27        11.03     13772.61         1.44241         1.14300         6.23900        14.33500      1599.14
```

**Latency percentiles** — a percentile `pXX` answers: *"XX% of requests
completed at or below this latency."*

- **p50** (median): 1.143 ms → half of all requests were faster than this.
- **p99**: 6.239 ms → 99% of requests were faster than this — only the
  slowest 1% took longer.
- **p99.9**: 14.335 ms → 99.9% of requests were faster than this — only
  1 request in 1000 (the *tail latency*) took longer.

**Why percentiles matter more than the average**: the average here
(1.442 ms) sits close to the median, but is pulled slightly upward by a
small number of slow outliers. The gap between p50 (1.14 ms) and p99.9
(14.3 ms) — roughly **12x** — shows that a small fraction of requests are
much slower than "typical". At real-world scale (thousands of
requests/sec), that "rare" 0.1% tail isn't a one-off — it happens
continuously to *someone*. This is why, when evaluating a system's
performance, **looking only at the average can hide real problems that
percentiles reveal** (caused by things like GC pauses, lock contention,
network jitter, etc.) — a point worth making explicitly in an interview.

**Other columns**:
- **Hits/sec / Misses/sec**: only meaningful for `Gets` (a `Sets` always
  writes, so hit/miss doesn't apply — hence `---`). Consistent with what
  we already know: the key range is far larger than the data written, so
  almost every GET misses.
- **Waits**: `0.00` because `--wait-ratio` (which exercises the Redis
  `WAIT` command, used to check replica acknowledgment) wasn't used here.
- **KB/sec**: throughput measured in data volume, not just operation count.

### A debugging detour worth documenting: why did `DBSIZE` stay fixed?

While verifying the run (see §3 below), running the **exact same command
twice** produced the **exact same `DBSIZE` (5000)** both times, and a third
run with an explicit, much wider `--key-maximum=1000000` only grew it to
`9983` — far less than the ~180,000 new unique keys naive math would
predict for 200,000 SETs over a 1,000,000-key range.

Working hypothesis: the effective default key range on this particular
`memtier_benchmark` build is considerably smaller than the officially
documented default (10,000,000) — likely close to 5,000. With 200,000 SET
attempts against only ~5,000 possible key IDs, essentially *every* key
gets hit at least once (a "coupon collector" saturation), so the result
is deterministic — which is exactly why two identical runs produced an
identical `DBSIZE`. This didn't block the exercise (data was populated,
and replication was verified either way, see below), but it's a good
example of **not trusting documented defaults blindly** and instead
verifying actual behaviour empirically against the specific binary in the
lab.

## 3. Verifying the replication actually works

### Don't trust the dashboard gauge alone

After running `memtier_benchmark`, the Secure UI's "Memory used" gauge
showed very different values for `source-db` (~16.7 MB) and `replica-db`
(~2.0 MB) — at first glance this looks like the replica is *not* catching
up. This metric on its own isn't reliable enough to conclude that,
though: dashboard gauges are often refreshed on an interval, cached by
the browser, or computed slightly differently depending on a shard's
role. It's not proof of anything by itself.

### Use `DBSIZE` as the source of truth

The authoritative way to compare two Redis datasets is to query them
directly. From a Redis Enterprise node (reachable via the bastion, e.g.
`ssh re-n1`) or from the `load` node:

```bash
redis-cli -h redis-10649.re-cluster1.ps-redislabs.org -p 10649 DBSIZE   # source-db
redis-cli -h redis-16135.re-cluster1.ps-redislabs.org -p 16135 DBSIZE   # replica-db
```

`DBSIZE` returns the exact number of keys in the dataset — a direct,
unambiguous data point, unlike a memory-usage percentage.

### Results across three independent runs

| Run | Parameters changed | `source-db` DBSIZE | `replica-db` DBSIZE | Match? |
|---|---|---|---|---|
| 1 | default key range | 5000 | 5000 | ✅ |
| 2 | identical command re-run | 5000 | 5000 | ✅ |
| 3 | `--key-minimum=1 --key-maximum=1000000` | 9983 | 9983 | ✅ |

Every single time, `source-db` and `replica-db` reported the **exact
same key count**, even across three runs with different parameters and
different resulting dataset sizes. This is strong, repeated evidence
that "Replica Of" isn't just doing a one-time initial sync — it's
continuously streaming every subsequent write, exactly as Redis
replication is supposed to.

### Lesson worth repeating in an interview

> "Dashboards can lag or aggregate data; whenever I need to *prove*
> correctness, I go straight to a direct command against the data itself
> (`DBSIZE`, `INFO replication`, etc.) instead of relying on a UI metric."

## 4. The Java program

### Why Quarkus, and why "command mode"

The program is a **Quarkus application**, not a plain `main()` with hand
managed dependencies. Reasoning:

- Quarkus gives CDI (dependency injection), externalized configuration
  (`application.properties`, with environment-variable overrides), and a
  managed Redis client **for free**, without pulling in a web server —
  none of that is needed here, so none of it is added (deliberately
  **no** `quarkus-rest`/`quarkus-resteasy` dependency).
- **Command mode** (`@QuarkusMain` + `implements QuarkusApplication`) is
  the official Quarkus way to write a CLI-style app: Quarkus boots its
  container, calls `run(String... args)` once, then shuts everything
  down and the JVM exits with the returned status code. See
  https://quarkus.io/guides/command-mode-reference.
- This is the same pattern reused for Exercises 2 and 3, for consistency:
  one Quarkus "command mode" module per exercise, configuration in
  `application.properties`, business logic kept in plain, framework-free
  classes wherever possible (see "Design" below).
- **Version pinned to Quarkus 3.33 (the current LTS line)**, not the
  newest 3.39/4.x, because the lab's VS Code IDE runs **Java 17** and
  Quarkus 4 raises its minimum Java version to 21. Quarkus 3.x fully
  supports Java 17.

### Redis client: `quarkus-redis-client`

Jedis (used in the first draft) was replaced with the **official,
upstream Quarkus Redis extension** (`io.quarkus:quarkus-redis-client`,
built on the Vert.x Redis client). Key points, useful to explain out loud:

- **Two named clients**, one per database, declared purely in
  `application.properties`:
  ```properties
  quarkus.redis.source.hosts=redis://${SOURCE_HOST:127.0.0.1}:${SOURCE_PORT:6379}
  quarkus.redis.replica.hosts=redis://${REPLICA_HOST:127.0.0.1}:${REPLICA_PORT:6379}
  ```
  Quarkus's configuration layer (SmallRye Config) resolves the
  `${SOURCE_HOST:127.0.0.1}` syntax against environment variables at
  startup — same `SOURCE_HOST`/`SOURCE_PORT`/`REPLICA_HOST`/`REPLICA_PORT`
  variables as before, just declared in config instead of read manually
  with `System.getenv(...)` in Java.
- Each named client is injected with the `@RedisClientName` qualifier:
  ```java
  @Inject @RedisClientName("source")  RedisDataSource source;
  @Inject @RedisClientName("replica") RedisDataSource replica;
  ```
- `RedisDataSource` is the **blocking**, typed API (there's also a
  `ReactiveRedisDataSource` — not needed for this simple, sequential
  exercise). Sorted-set commands are obtained via
  `redis.sortedSet(String.class)`, which returns a `SortedSetCommands`
  with typed `zadd(...)` / `zrange(...)` methods.
- **No `ZREVRANGE` method exists** in this typed API — Quarkus models it
  as `ZRANGE` plus the `REV` option: `zrange(key, start, stop, new
  ZRangeArgs().rev())`. Same single native Redis command under the hood
  (`ZRANGE ... REV`, available since Redis 6.2), just expressed
  differently in the Java API. Worth knowing this if asked "where's
  ZREVRANGE?" in an interview.

### Build

```bash
mvn -q package
```

Quarkus's own Maven plugin performs build-time "augmentation" during
`package` and produces a **`target/quarkus-app/`** directory (not a
single jar) containing `quarkus-run.jar`, `lib/`, and `app/`. This is the
standard, recommended Quarkus layout ("fast-jar") — since the code is now
delivered via `git clone` directly onto the machine that runs it (see the
git workflow discussed earlier), there's no need to shuttle around a
single self-contained file, so the default layout was kept as-is (no
`quarkus.package.jar.type=uber-jar` override).

### Run

```bash
SOURCE_HOST=<source-db-host>   SOURCE_PORT=<source-db-port> \
REPLICA_HOST=<replica-db-host> REPLICA_PORT=<replica-db-port> \
java -jar target/quarkus-app/quarkus-run.jar
```

Expected output:

```
Connecting to source-db ...
Inserting values 1..100 into source-db (Sorted Set 'numbers') ...
Done.
Connecting to replica-db ...
Values read back from replica-db, in reverse order:
[100, 99, 98, ..., 2, 1]
```

### Design

- `NumberRepository` — the contract: insert a range, read it back reversed.
  Plain interface, no Quarkus/Redis-client types in its signature.
- `RedisSortedSetGateway` — a narrow port exposing only `ZADD`/`ZREVRANGE`
  (the two Redis operations the exercise actually needs), independent of
  whichever Redis client sits behind it. It exists so unit tests never
  have to mock a large third-party client class directly (best practice:
  don't mock types you don't own).
- `QuarkusRedisSortedSetGateway` — a 1:1 adapter from that port to
  `quarkus-redis-client`'s `RedisDataSource`/`SortedSetCommands`.
- `SortedSetNumberRepository` — the actual logic: `ZADD key <value>
  <value>` for each number (1..100), `ZRANGE key 0 -1 REV` to read back
  reversed. **Completely unaware of Quarkus** — it only depends on the
  `RedisSortedSetGateway` port, which is exactly why swapping the
  underlying Redis client (Jedis → `quarkus-redis-client`) required
  **zero changes** to this class or to its tests.
- `Exercise1Main` — the `@QuarkusMain` entry point: injects the two named
  `RedisDataSource`s (`source`, `replica`), wires them into two
  `SortedSetNumberRepository` instances, and drives the exercise.

### Tests (TDD)

`SortedSetNumberRepositoryTest` was written **before** the implementation
and mocks only `RedisSortedSetGateway` — never Quarkus, never a real
Redis client. It describes exactly which Redis commands must be issued
and how results must be parsed. Run with:

```bash
mvn -q test
```

This test suite required **no changes at all** when the Redis client was
swapped from Jedis to `quarkus-redis-client` — direct proof that the
port/adapter split paid off.

## 5. Alternate Redis structures considered

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

## 6. Operational notes (lessons learned working in this lab)

Worth mentioning even though they're not strict exercise requirements —
they show real troubleshooting, which is exactly what a PS/consulting
role involves.

### The web terminal (Wetty) kept disconnecting every ~30 seconds

Symptom: the browser-based terminal to the bastion dropped the
connection roughly every 30 seconds, killing the shell (and its
in-memory `history`) each time — regardless of whether anything was
being typed.

- **Likely cause**: a fixed idle/keepalive timeout somewhere in the
  infrastructure in front of Wetty (many cloud HTTP(S) load balancers
  default to a 30-second backend timeout for long-lived/websocket
  connections unless explicitly tuned). Not something fixable from the
  browser side.
- **Workaround adopted**: instead of fighting the disconnect, make
  individual commands **survive** it:
  - Chain the "save the command to a file" step and the actual benchmark
    launch into **one pasted line**, so both happen even if the next
    disconnect is only a second away.
  - Use `nohup ... > logfile 2>&1 & disown` to detach the long-running
    process from the controlling shell/terminal entirely — once
    launched, it keeps running on the remote host independent of the
    browser session, and its output can be read back after reconnecting.
  - (If `tmux`/`screen` had been available on the `load` node, that would
    have been the more general solution — a persistent remote session
    you simply re-attach to after a disconnect. Neither was installed
    here, so `nohup`/`disown` was the pragmatic fallback for this
    specific, non-interactive command.)
- **Side effect noticed**: because the shell kept dying before bash could
  flush its history to `~/.bash_history`, the very first `memtier_benchmark`
  command run was "lost" from `history` — a good reminder that
  interactive shell history is not a reliable audit trail in an
  unstable-connection environment; the `/tmp/memtier_benchmark.txt` file
  (written proactively, on purpose, as part of the same one-shot command)
  is the actual reliable record, by design.
