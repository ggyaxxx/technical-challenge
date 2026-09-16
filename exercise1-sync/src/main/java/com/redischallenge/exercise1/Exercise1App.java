package com.redischallenge.exercise1;

import redis.clients.jedis.JedisPooled;

import java.util.List;

/**
 * Entry point for Exercise 1.
 *
 * What it does, in order:
 *   1. Connects to source-db and inserts the integers 1..100 into a Sorted Set.
 *   2. Connects to replica-db (which Redis Enterprise keeps in sync via
 *      "Replica Of") and reads that same Sorted Set back in descending order.
 *   3. Prints the result.
 *
 * Connection details are NOT hard-coded: source-db and replica-db only exist
 * once created in the lab's Secure UI, so host/port are passed in via
 * environment variables (with sane localhost defaults for quick local
 * smoke-testing against a throwaway Redis).
 *
 * Required env vars (all optional, defaults shown):
 *   SOURCE_HOST   (default: 127.0.0.1)
 *   SOURCE_PORT   (default: 6379)
 *   REPLICA_HOST  (default: 127.0.0.1)
 *   REPLICA_PORT  (default: 6379)
 */
public final class Exercise1App {

    private static final String KEY = "numbers";

    private Exercise1App() {
    }

    public static void main(String[] args) {
        String sourceHost = envOrDefault("SOURCE_HOST", "127.0.0.1");
        int sourcePort = Integer.parseInt(envOrDefault("SOURCE_PORT", "6379"));
        String replicaHost = envOrDefault("REPLICA_HOST", "127.0.0.1");
        int replicaPort = Integer.parseInt(envOrDefault("REPLICA_PORT", "6379"));

        System.out.printf("Connecting to source-db at %s:%d ...%n", sourceHost, sourcePort);
        try (JedisPooled source = new JedisPooled(sourceHost, sourcePort)) {
            NumberRepository sourceRepo =
                    new SortedSetNumberRepository(new JedisSortedSetGateway(source), KEY);

            System.out.println("Inserting values 1..100 into source-db (Sorted Set '" + KEY + "') ...");
            sourceRepo.insertRange(1, 100);
            System.out.println("Done.");
        }

        System.out.printf("Connecting to replica-db at %s:%d ...%n", replicaHost, replicaPort);
        try (JedisPooled replica = new JedisPooled(replicaHost, replicaPort)) {
            NumberRepository replicaRepo =
                    new SortedSetNumberRepository(new JedisSortedSetGateway(replica), KEY);

            List<Integer> reversed = replicaRepo.readAllReverse();
            System.out.println("Values read back from replica-db, in reverse order:");
            System.out.println(reversed);
        }
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
