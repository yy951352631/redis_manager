package com.shcj.cache.benchmark;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 压测指标累加器。
 *
 * <p>延迟用固定桶的直方图而不是保存每个样本：一次十分钟、十万 QPS 的压测会产生上亿个
 * 样本，存下来只为算几个分位数并不划算，而且分配本身就会干扰被测的延迟。
 *
 * <p>桶按微秒对数分布：0.1ms 以内一档、几毫秒一档、几十毫秒一档。Redis 正常延迟在
 * 微秒到毫秒级，慢的那一侧才需要区分度，均匀分桶会把有用的那一段全挤在第一个桶里。
 */
public class BenchmarkStats {

    /** 桶上界（微秒），最后一个桶收纳所有更慢的请求 */
    private static final long[] BUCKET_UPPER_US = {
            50, 100, 200, 300, 500, 750,
            1_000, 1_500, 2_000, 3_000, 5_000, 7_500,
            10_000, 15_000, 20_000, 30_000, 50_000, 75_000,
            100_000, 200_000, 500_000, 1_000_000, Long.MAX_VALUE
    };

    private final AtomicLongArray buckets = new AtomicLongArray(BUCKET_UPPER_US.length);
    private final AtomicLong totalCount = new AtomicLong();
    private final AtomicLong totalLatencyUs = new AtomicLong();
    private final AtomicLong maxLatencyUs = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();

    /** 按命令拆分的次数与耗时，用于结果里的逐命令明细 */
    private final Map<String, AtomicLong> commandCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> commandLatencyUs = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errorByType = new ConcurrentHashMap<>();

    public void record(String command, long latencyUs) {
        totalCount.incrementAndGet();
        totalLatencyUs.addAndGet(latencyUs);
        buckets.incrementAndGet(bucketOf(latencyUs));
        // CAS 更新最大值：直接比较后赋值在并发下会丢更新
        long previousMax;
        do {
            previousMax = maxLatencyUs.get();
            if (latencyUs <= previousMax) {
                break;
            }
        } while (!maxLatencyUs.compareAndSet(previousMax, latencyUs));

        counter(commandCount, command).incrementAndGet();
        counter(commandLatencyUs, command).addAndGet(latencyUs);
    }

    public void recordError(String type) {
        errorCount.incrementAndGet();
        counter(errorByType, type == null ? "unknown" : type).incrementAndGet();
    }

    /** 快捷压测每档使用独立统计器，结束后合并成全程汇总。 */
    public void mergeFrom(BenchmarkStats source) {
        if (source == null || source == this) {
            return;
        }
        totalCount.addAndGet(source.totalCount.get());
        totalLatencyUs.addAndGet(source.totalLatencyUs.get());
        errorCount.addAndGet(source.errorCount.get());
        for (int i = 0; i < buckets.length(); i++) {
            buckets.addAndGet(i, source.buckets.get(i));
        }
        updateMax(source.maxLatencyUs.get());
        mergeCounters(commandCount, source.commandCount);
        mergeCounters(commandLatencyUs, source.commandLatencyUs);
        mergeCounters(errorByType, source.errorByType);
    }

    private void mergeCounters(Map<String, AtomicLong> target, Map<String, AtomicLong> source) {
        for (Map.Entry<String, AtomicLong> entry : source.entrySet()) {
            counter(target, entry.getKey()).addAndGet(entry.getValue().get());
        }
    }

    private void updateMax(long candidate) {
        long previous;
        do {
            previous = maxLatencyUs.get();
            if (candidate <= previous) {
                return;
            }
        } while (!maxLatencyUs.compareAndSet(previous, candidate));
    }

    private AtomicLong counter(Map<String, AtomicLong> map, String key) {
        AtomicLong counter = map.get(key);
        if (counter == null) {
            AtomicLong created = new AtomicLong();
            AtomicLong existing = map.putIfAbsent(key, created);
            counter = existing == null ? created : existing;
        }
        return counter;
    }

    private int bucketOf(long latencyUs) {
        for (int i = 0; i < BUCKET_UPPER_US.length; i++) {
            if (latencyUs <= BUCKET_UPPER_US[i]) {
                return i;
            }
        }
        return BUCKET_UPPER_US.length - 1;
    }

    /**
     * 分位数。直方图给出的是桶上界，所以结果是「不超过这个值」的上界估计，
     * 不是精确样本值——分位数用于横向对比和判断量级，这个精度足够。
     */
    public long percentileUs(double percentile) {
        long total = totalCount.get();
        if (total <= 0) {
            return 0L;
        }
        long target = (long) Math.ceil(total * percentile / 100.0);
        long accumulated = 0;
        for (int i = 0; i < BUCKET_UPPER_US.length; i++) {
            accumulated += buckets.get(i);
            if (accumulated >= target) {
                return BUCKET_UPPER_US[i] == Long.MAX_VALUE ? maxLatencyUs.get() : BUCKET_UPPER_US[i];
            }
        }
        return maxLatencyUs.get();
    }

    public long getTotalCount() {
        return totalCount.get();
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public long getAttemptedCount() {
        return totalCount.get() + errorCount.get();
    }

    public long getMaxLatencyUs() {
        return maxLatencyUs.get();
    }

    public double getAvgLatencyUs() {
        long total = totalCount.get();
        return total <= 0 ? 0D : totalLatencyUs.get() * 1.0D / total;
    }

    public Map<String, Long> commandCounts() {
        return snapshot(commandCount);
    }

    public Map<String, Double> commandAvgLatencyUs() {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : commandCount.entrySet()) {
            long count = entry.getValue().get();
            AtomicLong latency = commandLatencyUs.get(entry.getKey());
            result.put(entry.getKey(), count <= 0 || latency == null ? 0D : latency.get() * 1.0D / count);
        }
        return result;
    }

    public Map<String, Long> errorTypes() {
        return snapshot(errorByType);
    }

    private Map<String, Long> snapshot(Map<String, AtomicLong> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : source.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }
}
