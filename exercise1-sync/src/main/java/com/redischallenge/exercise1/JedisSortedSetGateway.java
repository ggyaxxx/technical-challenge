package com.redischallenge.exercise1;

import redis.clients.jedis.UnifiedJedis;

import java.util.List;

/**
 * Thin adapter: translates our RedisSortedSetGateway port into real Jedis
 * calls. UnifiedJedis is the base class shared by Jedis, JedisPooled and
 * JedisCluster in Jedis 5.x, so this adapter works whether we connect with a
 * single connection or a pooled client.
 *
 * Deliberately has no logic worth unit-testing on its own (it is a 1:1
 * pass-through); it is exercised by running the app against the real
 * source-db / replica-db in the lab.
 */
public class JedisSortedSetGateway implements RedisSortedSetGateway {

    private final UnifiedJedis redis;

    public JedisSortedSetGateway(UnifiedJedis redis) {
        this.redis = redis;
    }

    @Override
    public void zadd(String key, double score, String member) {
        redis.zadd(key, score, member);
    }

    @Override
    public List<String> zrevrange(String key, long start, long stop) {
        return redis.zrevrange(key, start, stop);
    }
}
