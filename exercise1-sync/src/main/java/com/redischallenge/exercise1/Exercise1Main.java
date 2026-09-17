package com.redischallenge.exercise1;

import io.quarkus.redis.client.RedisClientName;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Entry point for Exercise 1, running as a Quarkus "command mode"
 * application: no HTTP server, no REST endpoint - Quarkus boots its CDI
 * container, runs run() below, then shuts down and the JVM exits.
 * (See: https://quarkus.io/guides/command-mode-reference)
 *
 * What it does, in order:
 *   1. Connects to source-db (the "source" named Redis client) and inserts
 *      the integers 1..100 into a Sorted Set.
 *   2. Connects to replica-db (the "replica" named Redis client - kept in
 *      sync by Redis Enterprise's "Replica Of" feature) and reads that same
 *      Sorted Set back in descending order.
 *   3. Prints the result.
 *
 * Connection details come from application.properties (quarkus.redis.source.hosts
 * / quarkus.redis.replica.hosts), which in turn read the SOURCE_HOST/SOURCE_PORT/
 * REPLICA_HOST/REPLICA_PORT environment variables - see that file.
 */
@QuarkusMain
public class Exercise1Main implements QuarkusApplication {

    private static final String KEY = "numbers";

    @Inject
    @RedisClientName("source")
    RedisDataSource source;

    @Inject
    @RedisClientName("replica")
    RedisDataSource replica;

    @Override
    public int run(String... args) {
        System.out.println("Connecting to source-db ...");
        NumberRepository sourceRepo =
                new SortedSetNumberRepository(new QuarkusRedisSortedSetGateway(source), KEY);

        System.out.println("Inserting values 1..100 into source-db (Sorted Set '" + KEY + "') ...");
        sourceRepo.insertRange(1, 100);
        System.out.println("Done.");

        System.out.println("Connecting to replica-db ...");
        NumberRepository replicaRepo =
                new SortedSetNumberRepository(new QuarkusRedisSortedSetGateway(replica), KEY);

        List<Integer> reversed = replicaRepo.readAllReverse();
        System.out.println("Values read back from replica-db, in reverse order:");
        System.out.println(reversed);

        return 0;
    }
}
