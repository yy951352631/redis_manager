package com.shcj.cache.risk.evaluator;

import java.util.List;

/**
 * 时序增长趋势拟合：最小二乘直线，输出「每天增长多少」。
 *
 * <p>不用「末值 ÷ 首值」算增长——那等于把整段趋势押在两个采样点上，
 * 一次采集抖动、一次重启后的冷启动，都会把结论带偏。最小二乘用上全部样本，
 * 单点异常对斜率的影响被稀释。
 *
 * <p>斜率按天归一化而不是按窗口，这样 3 天窗口与 7 天窗口算出的数可以直接比较，
 * 阈值也只需要配一套。
 */
public final class GrowthTrend {

    /** 拟合至少需要这么多个样本，否则斜率没有统计意义 */
    public static final int MIN_SAMPLES = 6;

    private static final double MILLIS_PER_DAY = 24 * 3600 * 1000D;

    private final double slopePerDay;
    private final double meanValue;
    private final double firstValue;
    private final double lastValue;
    private final double windowDays;
    private final int sampleCount;

    private GrowthTrend(double slopePerDay, double meanValue, double firstValue, double lastValue,
                        double windowDays, int sampleCount) {
        this.slopePerDay = slopePerDay;
        this.meanValue = meanValue;
        this.firstValue = firstValue;
        this.lastValue = lastValue;
        this.windowDays = windowDays;
        this.sampleCount = sampleCount;
    }

    /**
     * @param points 每项为 {时间戳毫秒, 数值}，需按时间升序
     * @return 样本不足或时间跨度为 0 时返回 null
     */
    public static GrowthTrend fit(List<double[]> points) {
        if (points == null || points.size() < MIN_SAMPLES) {
            return null;
        }
        int n = points.size();
        double sumT = 0, sumV = 0;
        for (double[] p : points) {
            sumT += p[0];
            sumV += p[1];
        }
        double meanT = sumT / n;
        double meanV = sumV / n;

        double numerator = 0;
        double denominator = 0;
        for (double[] p : points) {
            double dt = p[0] - meanT;
            numerator += dt * (p[1] - meanV);
            denominator += dt * dt;
        }
        if (denominator == 0) {
            // 全部样本时间相同，无法拟合
            return null;
        }
        double slopePerMilli = numerator / denominator;
        double spanMillis = points.get(n - 1)[0] - points.get(0)[0];
        return new GrowthTrend(slopePerMilli * MILLIS_PER_DAY, meanV,
                points.get(0)[1], points.get(n - 1)[1], spanMillis / MILLIS_PER_DAY, n);
    }

    /**
     * 日均增长率（%），以窗口内均值为基准。
     *
     * <p>用均值而不是首值做分母：首值可能恰好是个低谷，会把增长率放大到失真。
     */
    public double dailyPercent() {
        if (meanValue <= 0) {
            return 0D;
        }
        return slopePerDay / meanValue * 100D;
    }

    /**
     * 按当前斜率推算还有多少天触及容量上限。
     *
     * @return 不增长、已超限或容量未知时返回 null
     */
    public Double daysToReach(double capacity) {
        if (capacity <= 0 || slopePerDay <= 0 || lastValue >= capacity) {
            return null;
        }
        return (capacity - lastValue) / slopePerDay;
    }

    public double getSlopePerDay() {
        return slopePerDay;
    }

    public double getMeanValue() {
        return meanValue;
    }

    public double getFirstValue() {
        return firstValue;
    }

    public double getLastValue() {
        return lastValue;
    }

    public double getWindowDays() {
        return windowDays;
    }

    public int getSampleCount() {
        return sampleCount;
    }
}
