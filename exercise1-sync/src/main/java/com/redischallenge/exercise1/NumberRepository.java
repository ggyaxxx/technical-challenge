package com.redischallenge.exercise1;

import java.util.List;

/**
 * Abstraction over "store a range of numbers in Redis, then read them back".
 * Kept as an interface on purpose: it lets us write unit tests against a mock,
 * without needing a real Redis connection, while the real implementation talks
 * to Redis using only officially supported commands.
 */
public interface NumberRepository {

    /**
     * Inserts every integer in the inclusive range [from, to] into Redis.
     */
    void insertRange(int from, int to);

    /**
     * Reads back everything previously inserted, in descending order.
     */
    List<Integer> readAllReverse();
}
