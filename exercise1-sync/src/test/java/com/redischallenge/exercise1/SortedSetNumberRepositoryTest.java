package com.redischallenge.exercise1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD step 1 (RED): these tests are written BEFORE SortedSetNumberRepository
 * exists / behaves correctly. They describe, in plain terms, the contract we
 * want:
 *   - insertRange(1, 100) must ask Redis to ZADD each value into a Sorted Set,
 *     using the value itself as both the score and the member.
 *   - readAllReverse() must ask Redis for ZREVRANGE (native "reverse order"
 *     read, no client-side sorting) and map the String members back to
 *     Integers, preserving Redis's order.
 *
 * We mock our own RedisSortedSetGateway port (not Jedis' UnifiedJedis
 * directly - see its Javadoc for why), so these tests run with zero network
 * calls and zero real Redis instance required.
 */
@ExtendWith(MockitoExtension.class)
class SortedSetNumberRepositoryTest {

    private static final String KEY = "numbers";

    @Mock
    private RedisSortedSetGateway redis;

    @Test
    void insertRange_addsEveryValueToTheSortedSetUsingValueAsScore() {
        NumberRepository repo = new SortedSetNumberRepository(redis, KEY);

        repo.insertRange(1, 5);

        // score == member value, so Redis's native ordering already matches
        // numeric ordering: no client-side sorting will ever be needed.
        verify(redis).zadd(KEY, 1.0, "1");
        verify(redis).zadd(KEY, 2.0, "2");
        verify(redis).zadd(KEY, 3.0, "3");
        verify(redis).zadd(KEY, 4.0, "4");
        verify(redis).zadd(KEY, 5.0, "5");
        verifyNoMoreInteractions(redis);
    }

    @Test
    void insertRange_withFullExerciseRange_callsZaddExactlyOneHundredTimes() {
        NumberRepository repo = new SortedSetNumberRepository(redis, KEY);

        repo.insertRange(1, 100);

        verify(redis, times(100)).zadd(eq(KEY), anyDouble(), anyString());
    }

    @Test
    void readAllReverse_delegatesToZrevrangeAndParsesMembersAsIntegers() {
        when(redis.zrevrange(KEY, 0, -1))
                .thenReturn(List.of("100", "99", "3", "2", "1"));

        NumberRepository repo = new SortedSetNumberRepository(redis, KEY);
        List<Integer> result = repo.readAllReverse();

        assertEquals(List.of(100, 99, 3, 2, 1), result);
        verify(redis).zrevrange(KEY, 0, -1);
    }

    @Test
    void readAllReverse_onEmptySet_returnsEmptyList() {
        when(redis.zrevrange(KEY, 0, -1)).thenReturn(List.of());

        NumberRepository repo = new SortedSetNumberRepository(redis, KEY);

        assertTrue(repo.readAllReverse().isEmpty());
    }
}
