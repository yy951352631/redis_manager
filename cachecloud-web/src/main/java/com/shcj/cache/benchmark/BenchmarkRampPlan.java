package com.shcj.cache.benchmark;

/**
 * 快捷压测的爬坡策略与停止判据。
 *
 * <p>做法是「逐档加并发，直到再加也没用」：每一档跑固定时长，记下 QPS、P99 和目标节点
 * 的 CPU，满足任一停止条件就收手，取各档中的最高 QPS 作为承载峰值。
 *
 * <p>为什么用倍增而不是线性加：Redis 的吞吐拐点往往出现在某个量级上而不是某个具体数值，
 * 线性加 10 要跑几十档才能覆盖到几百并发，倍增七档就能从 10 覆盖到 640。
 */
public final class BenchmarkRampPlan {

    /**
     * 并发档位，从 1 起倍增。
     *
     * <p>起点必须够低：默认 Pipeline 为 20，实测并发 10 就把单节点打到 93% CPU、
     * 一档就触顶，只留下一个数据点，既看不出曲线，也答不出「几个连接就压满了」。
     * 从 1 起才能观察到爬升过程并定位到最小的饱和并发。
     *
     * <p>上限 512 —— 再往上瓶颈基本都在压测端而不是 Redis。</p>
     */
    public static final int[] CONCURRENCY_STEPS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512};

    /** 每档时长。太短会把建连和预热算进来，太长则整轮拖到十几分钟 */
    public static final int STEP_SECONDS = 20;

    /**
     * CPU 打满判据。
     *
     * <p>阈值按单核算：Redis 执行命令是单线程的，主线程吃满一个核就是它的天花板，
     * 后台线程（AOF 刷盘、惰性释放）另算。所以这里的 90 指的是「一个核的九成」，
     * 不是整机 CPU 的九成。
     */
    public static final double CPU_SATURATED_PERCENT = 90.0D;

    /**
     * 拐点判据：并发翻倍而 QPS 涨幅不足此比例，说明已经压不动了。
     *
     * <p>5% 是留给采样噪声的余量——并发翻倍却只换来个位数的提升，多出来的并发
     * 只是在排队，继续加下去只会让延迟恶化而吞吐不动。
     */
    public static final double QPS_GAIN_THRESHOLD = 5.0D;

    /** 错误率超过此比例即停：再压下去测的是错误处理，不是吞吐 */
    public static final double ERROR_RATE_THRESHOLD = 1.0D;

    private BenchmarkRampPlan() {
    }

    /** 判断是否该停，返回停止原因；返回 null 表示继续加压 */
    public static String stopReason(double cpuPercent, long qps, long previousQps,
                                    long errorCount, long totalCount) {
        if (cpuPercent >= CPU_SATURATED_PERCENT) {
            return String.format("目标节点 CPU 达到 %.1f%%（单核），已打满", cpuPercent);
        }
        if (totalCount > 0) {
            double errorRate = errorCount * 100.0D / totalCount;
            if (errorRate > ERROR_RATE_THRESHOLD) {
                return String.format("错误率 %.2f%% 超过阈值，停止加压", errorRate);
            }
        }
        if (previousQps > 0) {
            double gain = (qps - previousQps) * 100.0D / previousQps;
            if (gain < QPS_GAIN_THRESHOLD) {
                return String.format("并发翻倍后 QPS 仅变化 %.1f%%，已到拐点", gain);
            }
        }
        return null;
    }
}
