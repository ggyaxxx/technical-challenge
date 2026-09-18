package com.redischallenge.exercise2;

import com.redischallenge.exercise2.dto.BdbDto;
import com.redischallenge.exercise2.dto.RedisUserDto;
import com.redischallenge.exercise2.dto.RoleDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapter: translates the ClusterApiGateway port into calls on
 * RestClusterApiClient (the MicroProfile REST Client), including the HTTP
 * Basic Authorization header built from the cluster admin credentials.
 *
 * Same role as QuarkusRedisSortedSetGateway in exercise1-sync: it is the
 * only class in this module that knows both "the port" and "the concrete
 * REST client" - Exercise2Workflow and its tests never see either the REST
 * client or HTTP details directly.
 */
@ApplicationScoped
public class RestClusterApiGateway implements ClusterApiGateway {

    private final RestClusterApiClient client;
    private final String authorizationHeader;

    /**
     * Cache of Role object uid -> management level (e.g. 1 -> "admin"),
     * lazily populated from {@code GET /v1/roles} on first use. This
     * gateway is @ApplicationScoped and this program performs a single
     * run, so a plain lazily-initialized field (no eviction) is enough -
     * see {@link #resolveRoleUid} and {@link #displayRole}.
     */
    private Map<Integer, String> managementByRoleUid;

    public RestClusterApiGateway(
            @RestClient RestClusterApiClient client,
            @ConfigProperty(name = "cluster.admin.email") String adminEmail,
            @ConfigProperty(name = "cluster.admin.password") String adminPassword) {
        this.client = client;
        String credentials = adminEmail + ":" + adminPassword;
        this.authorizationHeader = "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int createDatabase(String name, long memorySizeBytes) {
        BdbDto created = call(() -> client.createDatabase(authorizationHeader, new BdbDto(name, memorySizeBytes)));
        return created.uid;
    }

    @Override
    public void deleteDatabase(int uid) {
        callWithRetryOnConflict(() -> {
            client.deleteDatabase(authorizationHeader, uid);
            return null;
        });
    }

    /**
     * Creates a user, or reuses the existing one if a user with that email
     * already exists.
     *
     * Unlike the database created in this exercise, users are not deleted
     * at the end of the run (the exercise only asks for the database to be
     * cleaned up), so they persist on the cluster across runs. Users API
     * emails are unique cluster-wide, so re-running this program a second
     * time - the normal way to exercise a "create resources" script - hit
     * {@code 400} / {@code email_already_exists} for all three users on
     * every run after the first. Since the desired end state ("these three
     * users exist, with these roles") is the same either way, an existing
     * user is treated as success rather than a failure, making the whole
     * program safe to run repeatedly.
     */
    @Override
    public int createUser(String email, String name, String password, String role) {
        int roleUid = resolveRoleUid(role);
        try {
            RedisUserDto created = call(
                    () -> client.createUser(authorizationHeader, new RedisUserDto(email, name, password, roleUid)));
            return created.uid;
        } catch (RuntimeException creationFailure) {
            if (!isEmailAlreadyExists(creationFailure)) {
                throw creationFailure;
            }
            return findUserUidByEmail(email).orElseThrow(() -> creationFailure);
        }
    }

    private boolean isEmailAlreadyExists(RuntimeException e) {
        return e.getMessage() != null && e.getMessage().contains("email_already_exist");
    }

    private Optional<Integer> findUserUidByEmail(String email) {
        return call(() -> client.listUsers(authorizationHeader)).stream()
                .filter(dto -> email.equals(dto.email))
                .map(dto -> dto.uid)
                .findFirst();
    }

    @Override
    public List<UserView> listUsers() {
        ensureRolesLoaded();
        return call(() -> client.listUsers(authorizationHeader)).stream()
                .map(dto -> new UserView(dto.name, displayRole(dto), dto.email))
                .toList();
    }

    /**
     * Maps a desired management level (e.g. "db_viewer") to the uid of a
     * matching Role object, creating that Role if none exists yet.
     *
     * This cluster has RBAC enabled ({@code GET /v1/roles} succeeds and
     * returns Role objects), so the Users API's plain {@code role} string
     * is rejected as a reference to a non-existing Role by that name
     * (only "Admin" exists by default). Per the API reference, RBAC
     * clusters require {@code role_uids} instead - see RedisUserDto's
     * Javadoc. Since the exercise only names management levels
     * ("db_viewer", "db_member", "admin"), not pre-existing Role uids, a
     * Role with that name/management is created on demand.
     */
    private synchronized int resolveRoleUid(String management) {
        ensureRolesLoaded();
        for (Map.Entry<Integer, String> entry : managementByRoleUid.entrySet()) {
            if (entry.getValue().equals(management)) {
                return entry.getKey();
            }
        }
        RoleDto created = call(() -> client.createRole(authorizationHeader, new RoleDto(management, management)));
        managementByRoleUid.put(created.uid, created.management);
        return created.uid;
    }

    private synchronized void ensureRolesLoaded() {
        if (managementByRoleUid == null) {
            managementByRoleUid = new HashMap<>();
            for (RoleDto role : call(() -> client.listRoles(authorizationHeader))) {
                managementByRoleUid.put(role.uid, role.management);
            }
        }
    }

    /**
     * Displays a user's role as a management-level string. RBAC-enabled
     * clusters return {@code role_uids} instead of {@code role} (per the
     * Users API reference), so the first role uid is resolved back to its
     * management level through the same cache used by
     * {@link #resolveRoleUid}.
     */
    private String displayRole(RedisUserDto dto) {
        if (dto.role != null) {
            return dto.role;
        }
        if (dto.roleUids != null && !dto.roleUids.isEmpty()) {
            return managementByRoleUid.getOrDefault(dto.roleUids.get(0), "unknown");
        }
        return "none";
    }

    /**
     * Runs a REST client call and, on a non-2xx response, replaces the
     * generic "Bad Request"/"Not Found" exception from the client with one
     * that includes the response body. The Cluster Manager API returns a
     * JSON payload with an "error_code" and "description" on failures
     * (e.g. missing_memory_size, invalid_role); without reading it, all
     * that reaches the console is the HTTP status line, which is not
     * enough to diagnose what the request got wrong.
     */
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (WebApplicationException e) {
            String body = e.getResponse().readEntity(String.class);
            throw new RuntimeException(
                    "Cluster Manager API call failed: HTTP " + e.getResponse().getStatus() + " - " + body, e);
        }
    }

    private static final int DELETE_MAX_ATTEMPTS = 5;
    private static final long DELETE_RETRY_DELAY_MS = 2000;

    /**
     * Like {@link #call}, but retries a fixed number of times, with a
     * fixed delay, when the response is {@code 409 Conflict}.
     *
     * A database created and then immediately deleted (as this exercise
     * does) can briefly be in a "busy" state on the cluster side while
     * its shards finish provisioning: {@code DELETE /v1/bdbs/{uid}}
     * responds with {@code 409} / {@code {"error_code":"db_busy"}} until
     * that settles. This is a transient condition specific to a
     * delete-right-after-create sequence, not a request error, so it is
     * retried here instead of being surfaced through {@link #call}, which
     * is for reporting genuine, non-retryable request errors.
     */
    private <T> T callWithRetryOnConflict(Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (WebApplicationException e) {
                String body = e.getResponse().readEntity(String.class);
                boolean conflict = e.getResponse().getStatus() == 409;
                if (!conflict || attempt >= DELETE_MAX_ATTEMPTS) {
                    throw new RuntimeException(
                            "Cluster Manager API call failed: HTTP " + e.getResponse().getStatus() + " - " + body,
                            e);
                }
                System.out.println("Database busy, retrying delete in "
                        + (DELETE_RETRY_DELAY_MS / 1000) + "s (attempt " + attempt + "/" + DELETE_MAX_ATTEMPTS
                        + ") ...");
                sleep(DELETE_RETRY_DELAY_MS);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting to retry a busy database delete", interrupted);
        }
    }
}
