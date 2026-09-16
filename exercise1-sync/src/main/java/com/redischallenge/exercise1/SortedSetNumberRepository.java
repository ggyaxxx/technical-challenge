package com.redischallenge.exercise1;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stores numbers in a Redis Sorted Set (ZSET) and reads them back using
 * Redis's own native descending-order command.
 *
 * Why a Sorted Set (see README.md for the full discussion of alternatives):
 *  - We use the number itself as both the score and the member. That means
 *    Redis keeps the set ordered for us; there is no client-side sorting.
 *  - Reading in reverse order is then a single, native, fully-supported
 *    Redis command: ZREVRANGE key 0 -1. No extra logic required.
 */
public class SortedSetNumberRepository implements NumberRepository {

    private final RedisSortedSetGateway redis;
    private final String key;

    public SortedSetNumberRepository(RedisSortedSetGateway redis, String key) {
        this.redis = redis;
        this.key = key;
    }

    @Override
    public void insertRange(int from, int to) {
        for (int value = from; value <= to; value++) {
            // score == member value on purpose: it makes ordering trivial.
            redis.zadd(key, value, String.valueOf(value));
        }
    }

    @Override
    public List<Integer> readAllReverse() {
        List<String> membersDescending = redis.zrevrange(key, 0, -1);
        return membersDescending.stream()
                .map(Integer::parseInt)
                .collect(Collectors.toList());
    }
}
