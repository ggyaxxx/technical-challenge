package com.redischallenge.exercise2;

import com.redischallenge.exercise2.dto.BdbDto;
import com.redischallenge.exercise2.dto.RedisUserDto;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * Declarative MicroProfile REST Client for the Redis Enterprise Cluster
 * Manager REST API: https://<cluster>:9443/v1/...
 *
 * "configKey" ties this interface to the connection settings declared in
 * application.properties under quarkus.rest-client.cluster-api.* (base URL,
 * TLS trust settings) - see that file for details.
 *
 * The Authorization header (HTTP Basic Auth with the cluster admin's
 * credentials) is passed explicitly as a method parameter on every call,
 * rather than through a header-injecting filter, so the authentication
 * mechanism stays visible at the call site instead of being implicit.
 */
@Path("/v1")
@RegisterRestClient(configKey = "cluster-api")
public interface RestClusterApiClient {

    @POST
    @Path("/bdbs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    BdbDto createDatabase(@HeaderParam("Authorization") String authorization, BdbDto request);

    @DELETE
    @Path("/bdbs/{uid}")
    void deleteDatabase(@HeaderParam("Authorization") String authorization, @PathParam("uid") int uid);

    @POST
    @Path("/users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    RedisUserDto createUser(@HeaderParam("Authorization") String authorization, RedisUserDto request);

    @GET
    @Path("/users")
    @Produces(MediaType.APPLICATION_JSON)
    List<RedisUserDto> listUsers(@HeaderParam("Authorization") String authorization);
}
