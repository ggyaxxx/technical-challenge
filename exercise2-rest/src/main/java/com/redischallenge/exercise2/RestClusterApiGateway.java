package com.redischallenge.exercise2;

import com.redischallenge.exercise2.dto.BdbDto;
import com.redischallenge.exercise2.dto.RedisUserDto;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

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
        BdbDto created = client.createDatabase(authorizationHeader, new BdbDto(name, memorySizeBytes));
        return created.uid;
    }

    @Override
    public void deleteDatabase(int uid) {
        client.deleteDatabase(authorizationHeader, uid);
    }

    @Override
    public int createUser(String email, String name, String password, String role) {
        RedisUserDto created = client.createUser(authorizationHeader, new RedisUserDto(email, name, password, role));
        return created.uid;
    }

    @Override
    public List<UserView> listUsers() {
        return client.listUsers(authorizationHeader).stream()
                .map(dto -> new UserView(dto.name, dto.role, dto.email))
                .toList();
    }
}
