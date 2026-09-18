# Exercise 2 — Working with Redis REST API

## 0. Scope

This exercise uses the Redis Enterprise Cluster Manager REST API
(`https://re-cluster1.ps-redislabs.org:9443/v1/...`), not the Redis data
protocol used in Exercise 1. The program:

1. Creates a new database without modules (Database API).
2. Creates three users with specific email/name/role combinations (Users API).
3. Lists all users and displays them as name, role, email (Users API).
4. Deletes the database created in step 1 (Database API).

## 1. Architecture

Same pattern as `exercise1-sync`: a Quarkus command-mode application, with
the business workflow kept independent of the HTTP client through a
narrow port interface.

- `ClusterApiGateway` — port interface exposing exactly the four Cluster
  Manager operations this exercise needs (`createDatabase`,
  `deleteDatabase`, `createUser`, `listUsers`).
- `RestClusterApiClient` — declarative MicroProfile REST Client interface
  (`quarkus-rest-client-jackson`) mapping directly to
  `POST/DELETE /v1/bdbs` and `GET/POST /v1/users`.
- `RestClusterApiGateway` — adapter implementing `ClusterApiGateway` on
  top of `RestClusterApiClient`; also builds the HTTP Basic
  `Authorization` header from the configured cluster admin credentials.
- `BdbDto` / `RedisUserDto` — minimal JSON request/response DTOs, limited
  to the fields this exercise reads or writes. `@JsonIgnoreProperties(ignoreUnknown = true)`
  makes them tolerant of the many additional fields the real API returns.
  `@JsonInclude(NON_NULL)` omits fields that are `null` at request time
  (in particular `uid`, which is always `null` on create requests)
  instead of serializing them as an explicit JSON `null` — see section 2.
- `Exercise2Workflow` — the business logic, with no dependency on
  Quarkus, HTTP, or JSON. It only depends on `ClusterApiGateway`.
- `Exercise2Main` — the `@QuarkusMain` entry point, wiring the injected
  `ClusterApiGateway` into `Exercise2Workflow` and driving the four steps.

This mirrors `exercise1-sync`'s `RedisSortedSetGateway` /
`QuarkusRedisSortedSetGateway` / `SortedSetNumberRepository` split: the
workflow is unit-testable with a mocked gateway, with no network calls
and no dependency on the concrete REST client.

## 2. Database creation without modules

The Database API request body is:

```json
{
    "name": "exercise2-db",
    "type": "redis",
    "memory_size": 104857600
}
```

There is no `module_list` field. Per the Database API reference, omitting
`module_list` is precisely how a database with no modules is requested —
there is no separate "disable modules" flag to set.

There is also no `uid` field in the serialized request, even though
`BdbDto` declares one. `uid` is assigned by the cluster and is `null` in
Java before creation; without `@JsonInclude(NON_NULL)` on the DTO,
Jackson would still serialize it as `"uid": null`. The Database API
rejects a request body containing `"uid": null` with `400 Bad Request` —
a null `uid` is not treated the same as an absent one. The same applies
to `RedisUserDto` when creating users.

## 3. Users created

Exactly the three users specified by the exercise:

| Email | Name | Role |
|---|---|---|
| john.doe@example.com | John Doe | db_viewer |
| mike.smith@example.com | Mike Smith | db_member |
| cary.johnson@example.com | Cary Johnson | admin |

### RBAC and role resolution

This cluster has RBAC enabled: `GET /v1/roles` succeeds and returns Role
objects (confirmed by inspection — only a built-in `Admin` role, uid 1,
exists by default). On an RBAC-enabled cluster, the Users API's plain
`role` string field is not a fixed enum; it is resolved against existing
Role object names, and only `Admin` matches. Sending `"role": "db_viewer"`
is therefore rejected with `400 Bad Request` /
`{"error_code":"invalid_param","description":"Trying to associate with a
non-existing role"}`, since no Role named `db_viewer` exists.

Per the Users API reference, RBAC-enabled clusters must use `role_uids`
(an array of Role object uids) instead of `role`. Since the exercise
specifies management levels (`db_viewer`, `db_member`, `admin`) rather
than pre-existing Role uids, `RestClusterApiGateway.resolveRoleUid`:

1. Fetches all roles (`GET /v1/roles`) and looks for one whose
   `management` field matches the requested level.
2. If none exists, creates it (`POST /v1/roles` with
   `{"name": "<level>", "management": "<level>"}`) and uses the uid from
   the response.

The resulting uid is sent as `role_uids: [<uid>]` on the user creation
request. `listUsers()` reverses the same mapping to display a readable
role name, since RBAC-enabled clusters return `role_uids` instead of
`role` in the response body.

### Passwords

The Users API requires a non-empty `password` field on every create
request; the exercise text does not specify one. `Exercise2Workflow`
generates a fresh random password per user with `SecureRandom`, and does
not log or persist it. A fixed, hard-coded password was deliberately
avoided, since it would otherwise need to be committed to source control.
This is acceptable for these disposable lab users; it would not be an
appropriate approach for provisioning real accounts.

## 4. Listing users

`GET /v1/users` returns each user's full object (uid, email, name, role,
role_uids, email_alerts, auth_method, ...). `RestClusterApiGateway` maps
each entry to a `UserView(name, role, email)` record, matching the
"name, role, and email" display format requested by the exercise.
Example output:

```
Name: John Doe        Role: db_viewer  Email: john.doe@example.com
Name: Mike Smith      Role: db_member  Email: mike.smith@example.com
Name: Cary Johnson    Role: admin      Email: cary.johnson@example.com
```

## 5. Configuration

`src/main/resources/application.properties`:

```properties
quarkus.rest-client.cluster-api.url=${CLUSTER_API_URL:https://re-cluster1.ps-redislabs.org:9443}

quarkus.tls.cluster-api-tls.trust-all=true
quarkus.tls.cluster-api-tls.hostname-verification-algorithm=NONE
quarkus.rest-client.cluster-api.tls-configuration-name=cluster-api-tls

cluster.admin.email=${CLUSTER_ADMIN_EMAIL}
cluster.admin.password=${CLUSTER_ADMIN_PASSWORD}
```

- **`CLUSTER_API_URL`**: overrides the base URL. Defaults to the hostname
  given in the exercise text; substitute the cluster's IP address here if
  the hostname does not resolve from wherever this runs (this is a
  documented, real issue in this lab — see `exercise1-sync/README.md`,
  Redis Insight connectivity).
- **TLS trust settings**: the cluster's REST API is served over HTTPS
  with a self-signed certificate. `quarkus.tls.cluster-api-tls.trust-all=true`
  and `hostname-verification-algorithm=NONE` disable certificate and
  hostname validation for this named TLS configuration, which is then
  attached to the `cluster-api` REST client via `tls-configuration-name`.
  This is acceptable only for this lab exercise, never for a production
  endpoint.
- **`CLUSTER_ADMIN_EMAIL` / `CLUSTER_ADMIN_PASSWORD`**: cluster admin
  credentials, used to build the HTTP Basic `Authorization` header on
  every request. Unlike `exercise1-sync`'s unauthenticated Redis
  endpoints, these are real administrative credentials, so no default
  value is provided in `application.properties`, and they are not
  committed to source control. Both are required environment variables;
  if either is missing, Quarkus fails at startup with a configuration
  error instead of sending a blank or malformed `Authorization` header.

## 6. Error diagnostics

By default, the MicroProfile REST Client throws a
`ClientWebApplicationException` on any non-2xx response, whose message
contains only the HTTP status line (e.g. `Bad Request, status code 400`).
The Cluster Manager API returns a JSON body on errors with a machine
`error_code` and a human-readable `description` (e.g.
`missing_memory_size`, `invalid_role`, `uid_exists`); without reading it,
the status line alone is not enough to determine what was wrong with the
request.

`RestClusterApiGateway.call(...)` wraps every REST client invocation,
catches `WebApplicationException`, and re-throws a `RuntimeException`
that includes the response body, so the actual `error_code`/`description`
from the cluster reaches the console instead of only the status code.

## 7. Cleanup on failure

The database created in step 1 counts against the cluster's shard
license until it is deleted in step 4. If a step in between throws (for
example, user creation failing), a program that only calls
`deleteDatabase` at the very end never reaches it, leaving the database
behind — it keeps consuming license capacity indefinitely.

This is not a hypothetical: while diagnosing the `role`/`role_uids`
issue in section 3, two failed runs each left an orphaned `exercise2-db`
behind. Both were 1-shard databases (the minimum possible), but this
lab cluster's trial license permits only 4 shards total, and the two
pre-provisioned `exercise1-sync` databases already used the other 2 —
so the leaked databases alone exhausted the entire license, and any
further database creation failed with `invalid_param` /
"Total shards count exceeds amount of total shards permitted by
license", regardless of the size requested.

`Exercise2Main` now wraps steps 2–3 in a `try`/`catch`: if either step
fails, it attempts `deleteDatabase(databaseUid)` before re-throwing the
original failure, so the database does not outlive a failed run. A
cleanup failure inside that `catch` block is logged as a warning rather
than replacing the original exception, so the real root cause of the
failure is never masked by a secondary cleanup error.

### Retrying a delete against a busy database

`DELETE /v1/bdbs/{uid}` can respond `409 Conflict` /
`{"error_code":"db_busy"}` when called immediately after the database
was created and written to, while the cluster is still finishing shard
provisioning for it. This is exactly the sequence this exercise
performs (create, populate with users, delete), so it is expected to be
hit occasionally rather than being a request error.

`RestClusterApiGateway.callWithRetryOnConflict` is used only for
`deleteDatabase`: on `409`, it waits 2 seconds and retries, up to 5
attempts, before giving up and reporting the failure as usual. It is
not applied to `createDatabase`, `createUser`, or `createRole`, since
those are `POST` requests — blindly retrying a `POST` after an
ambiguous failure risks creating a duplicate resource, whereas `DELETE`
is naturally idempotent (deleting an already-deleted or not-yet-fully-up
resource has no additional side effect beyond the retry itself).

### Re-running the program: user creation is idempotent

The database created in step 1 is deleted in step 4 (or by the failure
cleanup in section 7), so it never outlives one run. The three users
created in step 2 are not deleted anywhere, by design — the exercise
only asks for the database to be cleaned up — so they persist on the
cluster across runs, and the Users API rejects a duplicate email with
`400` / `email_already_exists`. Running the program a second time is
the ordinary way to exercise a "create these resources" script, so
without handling this, every run after the first would fail at the
same point.

`RestClusterApiGateway.createUser` treats `email_already_exists`
specifically as success: it looks up the existing user by email via
`GET /v1/users` and returns that user's `uid` instead of propagating
the error. Any other failure from the create call is not treated this
way and is still reported as a genuine error. `resolveRoleUid` already
had the same property from the start (section 3): it looks for an
existing Role with the desired `management` level before creating one,
so re-running the program does not attempt to create duplicate roles
either.

## 8. Build, test, run

### Build

```bash
mvn -q package
```

### Tests (TDD)

`Exercise2WorkflowTest` was written before `Exercise2Workflow`, mocking
only `ClusterApiGateway`. It asserts:
- the Database API is called with no modules, using the expected name
  and memory size, and the returned uid is propagated;
- the Users API is called exactly three times, with the exact
  email/name/role combinations from the exercise text (the password
  value itself is not asserted, since the exercise does not specify one,
  only that each of the three calls receives a distinct generated value);
- `listUsers()` / `deleteDatabase()` delegate to the gateway unchanged.

```bash
mvn -q test
```

### Run

```bash
CLUSTER_ADMIN_EMAIL=admin@rl.org CLUSTER_ADMIN_PASSWORD=<secure-ui-password> \
java -jar target/quarkus-app/quarkus-run.jar
```

Expected output:

```
Creating database 'exercise2-db' (no modules) ...
Database created, uid=<n>
Creating the three required users ...
Users created.
Listing all users:
Name: John Doe        Role: db_viewer  Email: john.doe@example.com
Name: Mike Smith      Role: db_member  Email: mike.smith@example.com
Name: Cary Johnson    Role: admin      Email: cary.johnson@example.com
Deleting database uid=<n> ...
Database deleted.
```

### Inspecting the database before it is deleted

```bash
CLUSTER_ADMIN_EMAIL=admin@rl.org CLUSTER_ADMIN_PASSWORD=<secure-ui-password> \
java -jar target/quarkus-app/quarkus-run.jar --no-delete-db
```

`--no-delete-db` is a command-line argument to `Exercise2Main`, checked
directly against the `args` array `run(String... args)` already
receives — it needs no extra library or configuration property. With
it, step 4 (and the failure-cleanup delete in section 7) is skipped, so
the database created in step 1 is left on the cluster instead of being
removed at the end. This is a debugging aid, for confirming the database
was created as expected (e.g. in the Cluster Manager UI, or with
`redis-cli -h <host> -p <port>` against it), and is not needed for the
exercise itself, which always deletes the database it creates.

The program prints the exact `curl ... DELETE /v1/bdbs/<uid>` command to
remove it manually afterward, using the actual `uid` from that run.

## 9. Useful `curl` commands for manual verification

These query the same three Cluster Manager API resources this program
uses (`/v1/bdbs`, `/v1/users`, `/v1/roles`), independently of the Java
code — useful for confirming what the program did, or for diagnosing a
failure by hand. Credentials are read from the shell environment, not
written here, for the same reason `application.properties` does not
default them (section 5).

```bash
export CLUSTER_ADMIN_EMAIL=admin@rl.org
export CLUSTER_ADMIN_PASSWORD=<secure-ui-password>
export CLUSTER_HOST=re-cluster1.ps-redislabs.org
```

### Databases

```bash
curl -sk -u "$CLUSTER_ADMIN_EMAIL:$CLUSTER_ADMIN_PASSWORD" \
  "https://$CLUSTER_HOST:9443/v1/bdbs" \
  | python3 -c "
import json, sys
for b in json.load(sys.stdin):
    print(b['uid'], b['name'], 'shards_count=' + str(b.get('shards_count')),
          'replication=' + str(b.get('replication')))
"
```

### Users

```bash
curl -sk -u "$CLUSTER_ADMIN_EMAIL:$CLUSTER_ADMIN_PASSWORD" \
  "https://$CLUSTER_HOST:9443/v1/users" \
  | python3 -c "
import json, sys
for u in json.load(sys.stdin):
    print(u['uid'], u['name'], u['email'], 'role=' + str(u.get('role')),
          'role_uids=' + str(u.get('role_uids')))
"
```

### Roles

```bash
curl -sk -u "$CLUSTER_ADMIN_EMAIL:$CLUSTER_ADMIN_PASSWORD" \
  "https://$CLUSTER_HOST:9443/v1/roles" \
  | python3 -c "
import json, sys
for r in json.load(sys.stdin):
    print(r['uid'], r['name'], 'management=' + str(r.get('management')))
"
```

### Full JSON, unfiltered

Replace `/v1/bdbs` with `/v1/users` or `/v1/roles` as needed, for the
complete object (all fields the API returns, not just the ones above):

```bash
curl -sk -u "$CLUSTER_ADMIN_EMAIL:$CLUSTER_ADMIN_PASSWORD" \
  "https://$CLUSTER_HOST:9443/v1/bdbs" | python3 -m json.tool
```

### Deleting a specific resource

```bash
curl -sk -u "$CLUSTER_ADMIN_EMAIL:$CLUSTER_ADMIN_PASSWORD" \
  -X DELETE "https://$CLUSTER_HOST:9443/v1/bdbs/<uid>"
```
