package com.redischallenge.exercise2;

import com.redischallenge.exercise2.dto.BdbDto;
import com.redischallenge.exercise2.dto.RedisUserDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
        call(() -> {
            client.deleteDatabase(authorizationHeader, uid);
            return null;
        });
    }

    @Override
    public int createUser(String email, String name, String password, String role) {
        RedisUserDto created =
                call(() -> client.createUser(authorizationHeader, new RedisUserDto(email, name, password, role)));
        return created.uid;
    }

    @Override
    public List<UserView> listUsers() {
        return call(() -> client.listUsers(authorizationHeader)).stream()
                .map(dto -> new UserView(dto.name, dto.role, dto.email))
                .toList();
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
}
