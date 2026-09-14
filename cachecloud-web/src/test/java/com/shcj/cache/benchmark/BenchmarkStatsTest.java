package com.shcj.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标累加器的测试。
 *
 * <p>压测引擎是多线程的，累加器一旦有并发缺陷，表现出来是「总数对不上」这种
 * 事后无法追查的问题，所以并发正确性单独测。</p>
 */
public class BenchmarkStatsTest {

    @Test
    public void 分位数落在正确的量级上() {
        BenchmarkStats stats = new BenchmarkStats();
        // 99 个 100us 的快请求 + 1 个 50ms 的慢请求
        for (int i = 0; i < 99; i++) {
            stats.record("GET", 100);
        }
        stats.record("GET", 50_000);

        assertEquals(100, stats.percentileUs(50), "半数请求应当落在 100us 桶");
        assertEquals(100, stats.percentileUs(95));
        // 尾部那一个慢请求必须体现在 P99 之后，否则慢请求会被平均掉看不见
        assertTrue(stats.percentileUs(100) >= 50_000, "P100 应当反映最慢的那次");
        assertEquals(50_000, stats.getMaxLatencyUs());
    }

    @Test
    public void 平均值与最大值分别反映整体和尾部() {
        BenchmarkStats stats = new BenchmarkStats();
        stats.record("SET", 1_000);
        stats.record("SET", 3_000);
        assertEquals(2_000D, stats.getAvgLatencyUs(), 1e-9);
        assertEquals(3_000, stats.getMaxLatencyUs());
    }

    @Test
    public void 按命令拆分次数与均值() {
        BenchmarkStats stats = new BenchmarkStats();
        stats.record("GET", 100);
        stats.record("GET", 300);
        stats.record("SET", 1_000);

        Map<String, Long> counts = stats.commandCounts();
        assertEquals(2L, counts.get("GET").longValue());
        assertEquals(1L, counts.get("SET").longValue());
        assertEquals(200D, stats.commandAvgLatencyUs().get("GET"), 1e-9);
    }

    @Test
    public void 错误按类型归类() {
        BenchmarkStats stats = new BenchmarkStats();
        stats.recordError("JedisConnectionException");
        stats.recordError("JedisConnectionException");
        stats.recordError("JedisDataException");
        assertEquals(3L, stats.getErrorCount());
        assertEquals(2L, stats.errorTypes().get("JedisConnectionException").longValue());
    }

    @Test
    public void 并发累加不丢计数() throws Exception {
        BenchmarkStats stats = new BenchmarkStats();
        int threads = 16;
        int perThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        stats.record("GET", 100 + (i % 50));
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发累加超时");
        pool.shutdownNow();

        assertEquals((long) threads * perThread, stats.getTotalCount(), "总数丢失说明累加不是原子的");
        assertEquals((long) threads * perThread, stats.commandCounts().get("GET").longValue());
    }

    @Test
    public void 并发下最大值不丢更新() throws Exception {
        BenchmarkStats stats = new BenchmarkStats();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final long base = (t + 1) * 1000L;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 10_000; i++) {
                        stats.record("SET", base + i);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();
        // 最大值应当是所有线程里最大的那个：8000 + 9999
        assertEquals(17_999L, stats.getMaxLatencyUs());
    }

    @Test
    public void 没有样本时不做除零() {
        BenchmarkStats stats = new BenchmarkStats();
        assertEquals(0L, stats.percentileUs(99));
        assertEquals(0D, stats.getAvgLatencyUs(), 1e-9);
        assertEquals(0L, stats.getTotalCount());
    }

    @Test
    public void 合并升压档位后保留全部命令和错误统计() {
        BenchmarkStats first = new BenchmarkStats();
        first.record("HSET", 100);
        first.recordError("HINCRBY · JedisDataException");

        BenchmarkStats second = new BenchmarkStats();
        second.record("HINCRBY", 300);
        second.recordError("JedisConnectionException");

        BenchmarkStats aggregate = new BenchmarkStats();
        aggregate.mergeFrom(first);
        aggregate.mergeFrom(second);

        assertEquals(2L, aggregate.getTotalCount());
        assertEquals(2L, aggregate.getErrorCount());
        assertEquals(4L, aggregate.getAttemptedCount());
        assertEquals(1L, aggregate.commandCounts().get("HSET").longValue());
        assertEquals(1L, aggregate.commandCounts().get("HINCRBY").longValue());
        assertEquals(1L, aggregate.errorTypes().get("JedisConnectionException").longValue());
        assertEquals(300L, aggregate.getMaxLatencyUs());
        assertEquals(200D, aggregate.getAvgLatencyUs(), 1e-9);
    }
}
