package com.shcj.cache.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BenchmarkExecutorTest {

    @Test
    void hincrbyUsesFieldsThatCannotBeOverwrittenByHset() {
        for (int bucket = 0; bucket < 16; bucket++) {
            assertEquals("f" + bucket, BenchmarkExecutor.hashField(BenchmarkCommand.HSET, bucket));
            assertEquals("n" + bucket, BenchmarkExecutor.hashField(BenchmarkCommand.HINCRBY, bucket));
            assertNotEquals(
                    BenchmarkExecutor.hashField(BenchmarkCommand.HSET, bucket),
                    BenchmarkExecutor.hashField(BenchmarkCommand.HINCRBY, bucket));
        }
    }
}
