package com.redischallenge.exercise2;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/**
 * Entry point for Exercise 2, running as a Quarkus "command mode"
 * application - same pattern as Exercise1Main in exercise1-sync: Quarkus
 * boots its CDI container, runs run() below, then exits.
 *
 * Steps performed, in order, against the Redis Enterprise Cluster Manager
 * REST API (see application.properties for the endpoint/credentials):
 *   1. Create a new database, without modules (Database API).
 *   2. Create the three users specified by the exercise (Users API).
 *   3. List and display all users in "name, role, email" format (Users API).
 *   4. Delete the database created in step 1 (Database API).
 */
@QuarkusMain
public class Exercise2Main implements QuarkusApplication {

    @Inject
    ClusterApiGateway cluster;

    @Override
    public int run(String... args) {
        Exercise2Workflow workflow = new Exercise2Workflow(cluster);

        System.out.println("Creating database '" + Exercise2Workflow.NEW_DATABASE_NAME + "' (no modules) ...");
        int databaseUid = workflow.createDatabase();
        System.out.println("Database created, uid=" + databaseUid);

        try {
            System.out.println("Creating the three required users ...");
            workflow.createRequiredUsers();
            System.out.println("Users created.");

            System.out.println("Listing all users:");
            for (UserView user : workflow.listUsers()) {
                System.out.printf("Name: %-15s Role: %-10s Email: %s%n", user.name(), user.role(), user.email());
            }
        } catch (RuntimeException failure) {
            // The database created above counts against the cluster's shard
            // license until it is deleted. Without this cleanup, a failure
            // here (e.g. in user creation) leaves the database behind, and
            // it silently consumes license capacity that later runs need -
            // this is exactly what happened while debugging this exercise:
            // two earlier failed runs each left an orphaned "exercise2-db"
            // BDB, and a fourth database (regardless of size) then exceeded
            // the cluster's 4-shard license limit.
            System.err.println("Step failed after the database was created; attempting cleanup ...");
            try {
                workflow.deleteDatabase(databaseUid);
                System.err.println("Database uid=" + databaseUid + " deleted after failure.");
            } catch (RuntimeException cleanupFailure) {
                System.err.println("Warning: cleanup itself failed, database uid=" + databaseUid
                        + " was NOT deleted and must be removed manually: " + cleanupFailure.getMessage());
            }
            throw failure;
        }

        System.out.println("Deleting database uid=" + databaseUid + " ...");
        workflow.deleteDatabase(databaseUid);
        System.out.println("Database deleted.");

        return 0;
    }
}
