package com.redischallenge.exercise2;

import java.util.List;

/**
 * Narrow "port" exposing only the four Redis Enterprise Cluster Manager
 * REST API operations this exercise needs: create/delete a database
 * (Database API), and create/list users (Users API).
 *
 * Same design intent as RedisSortedSetGateway in exercise1-sync: business
 * logic (Exercise2Workflow) depends on this interface, not on the concrete
 * REST client, so it can be unit tested with a mock and with zero network
 * calls, and the HTTP/REST client implementation can change without
 * affecting the logic or its tests.
 */
public interface ClusterApiGateway {

    /**
     * Database API - POST /v1/bdbs. Creates a database with the given name
     * and memory limit, without specifying any module_list (no modules).
     *
     * @return the uid assigned by the cluster to the new database.
     */
    int createDatabase(String name, long memorySizeBytes);

    /**
     * Database API - DELETE /v1/bdbs/{uid}.
     */
    void deleteDatabase(int uid);

    /**
     * Users API - POST /v1/users.
     *
     * @return the uid assigned by the cluster to the new user.
     */
    int createUser(String email, String name, String password, String role);

    /**
     * Users API - GET /v1/users.
     */
    List<UserView> listUsers();
}
