package com.redischallenge.exercise1;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.sortedset.SortedSetCommands;
import io.quarkus.redis.datasource.sortedset.ZRangeArgs;

import java.util.List;

/**
 * Thin adapter: translates our RedisSortedSetGateway port into calls on
 * Quarkus's official Redis client (io.quarkus.redis.datasource.RedisDataSource,
 * built on the Vert.x Redis client - upstream Quarkus, fully supported).
 *
 * Same role as the old JedisSortedSetGateway, just backed by a different
 * (CDI-managed) Redis client. SortedSetNumberRepository and its tests don't
 * need to know or care which one is used - that's the whole point of the
 * RedisSortedSetGateway port.
 *
 * Note: Quarkus's SortedSetCommands has no dedicated "zrevrange" method;
 * ZREVRANGE's behaviour is obtained via ZRANGE with the REV argument
 * (new ZRangeArgs().rev()) - still the same, single, native Redis command
 * under the hood.
 */
public class QuarkusRedisSortedSetGateway implements RedisSortedSetGateway {

    private final SortedSetCommands<String, String> sortedSet;

    public QuarkusRedisSortedSetGateway(RedisDataSource redis) {
        this.sortedSet = redis.sortedSet(String.class);
    }

    @Override
    public void zadd(String key, double score, String member) {
        sortedSet.zadd(key, score, member);
    }

    @Override
    public List<String> zrevrange(String key, long start, long stop) {
        return sortedSet.zrange(key, start, stop, new ZRangeArgs().rev());
    }
}
