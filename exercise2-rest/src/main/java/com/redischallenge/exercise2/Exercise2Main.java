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

        System.out.println("Creating the three required users ...");
        workflow.createRequiredUsers();
        System.out.println("Users created.");

        System.out.println("Listing all users:");
        for (UserView user : workflow.listUsers()) {
            System.out.printf("Name: %-15s Role: %-10s Email: %s%n", user.name(), user.role(), user.email());
        }

        System.out.println("Deleting database uid=" + databaseUid + " ...");
        workflow.deleteDatabase(databaseUid);
        System.out.println("Database deleted.");

        return 0;
    }
}
