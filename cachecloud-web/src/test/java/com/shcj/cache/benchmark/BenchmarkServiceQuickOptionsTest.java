package com.shcj.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkServiceQuickOptionsTest {

    private final BenchmarkService service = new BenchmarkService();

    @Test
    void keepsSelectedCommandsForQuickBenchmark() {
        BenchmarkOptions options = service.buildQuickOptions(
                42L,
                Collections.singletonList("10.0.0.1:6379"),
                Arrays.asList("HGET", "HSET", "ZADD"));

        assertEquals(Arrays.asList("HGET", "HSET", "ZADD"), options.getCommands());
        assertEquals(Collections.singletonList("10.0.0.1:6379"), options.getTargetNodes());
        assertTrue(options.isQuickMode());
        assertEquals(BenchmarkRampPlan.CONCURRENCY_STEPS[0], options.getConcurrency());
    }

    @Test
    void fallsBackToLegacyCommandsWhenOlderClientOmitsSelection() {
        BenchmarkOptions options = service.buildQuickOptions(42L, null, null);

        assertEquals(Arrays.asList("GET", "SET"), options.getCommands());
        assertEquals(Collections.emptyList(), options.getTargetNodes());
    }
}
