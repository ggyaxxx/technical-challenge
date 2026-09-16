package com.redischallenge.exercise1;

import java.util.List;

/**
 * Narrow "port" exposing only the two Redis Sorted Set commands this
 * exercise needs: ZADD and ZREVRANGE.
 *
 * Why this exists instead of depending directly on Jedis' UnifiedJedis:
 *  - Best practice: don't mock types you don't own. UnifiedJedis is a huge
 *    third-party class (dozens of command interfaces); depending on it
 *    directly makes unit tests fragile and, on some JVMs, impossible to
 *    mock at all.
 *  - This interface documents exactly which two Redis commands our business
 *    logic relies on - both are official, fully-supported Redis commands.
 */
public interface RedisSortedSetGateway {

    /**
     * ZADD key score member - adds a member to a Sorted Set with the given score.
     */
    void zadd(String key, double score, String member);

    /**
     * ZREVRANGE key start stop - native Redis command to read Sorted Set
     * members ordered from highest to lowest score.
     */
    List<String> zrevrange(String key, long start, long stop);
}
