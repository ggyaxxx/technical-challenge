package com.redischallenge.exercise2;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Business logic for Exercise 2, independent of Quarkus and of the concrete
 * REST client - it only depends on the ClusterApiGateway port.
 *
 * Steps performed, matching the exercise text one to one:
 *   1. createDatabase()      - Database API: create a database, no modules.
 *   2. createRequiredUsers() - Users API: create the three specified users.
 *   3. listUsers()           - Users API: fetch all users for display.
 *   4. deleteDatabase(uid)   - Database API: delete the database created in
 *                              step 1.
 *
 * On passwords: the exercise does not specify passwords for the three
 * users, but the Redis Enterprise Users API requires one per user (a
 * non-empty "password" field is mandatory in the request body). Rather
 * than hard-coding a fixed password (which would then need to be
 * committed to source control), a fresh random password is generated per
 * user with SecureRandom and never persisted or logged in cleartext -
 * acceptable here because these are disposable lab users with no
 * production data behind them.
 */
public class Exercise2Workflow {

    public static final String NEW_DATABASE_NAME = "exercise2-db";
    public static final long NEW_DATABASE_MEMORY_SIZE_BYTES = 104_857_600L; // 100 MB

    private final ClusterApiGateway cluster;
    private final SecureRandom random = new SecureRandom();

    public Exercise2Workflow(ClusterApiGateway cluster) {
        this.cluster = cluster;
    }

    /**
     * Database API: creates a new database with no module_list (i.e. no
     * Redis modules attached), as required by the exercise.
     *
     * @return the uid of the newly created database.
     */
    public int createDatabase() {
        return cluster.createDatabase(NEW_DATABASE_NAME, NEW_DATABASE_MEMORY_SIZE_BYTES);
    }

    /**
     * Users API: creates exactly the three users specified by the exercise.
     */
    public void createRequiredUsers() {
        cluster.createUser("john.doe@example.com", "John Doe", generatePassword(), "db_viewer");
        cluster.createUser("mike.smith@example.com", "Mike Smith", generatePassword(), "db_member");
        cluster.createUser("cary.johnson@example.com", "Cary Johnson", generatePassword(), "admin");
    }

    /**
     * Users API: fetches all users currently defined on the cluster.
     */
    public List<UserView> listUsers() {
        return cluster.listUsers();
    }

    /**
     * Database API: deletes the database identified by uid.
     */
    public void deleteDatabase(int uid) {
        cluster.deleteDatabase(uid);
    }

    private String generatePassword() {
        byte[] randomBytes = new byte[18];
        random.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
