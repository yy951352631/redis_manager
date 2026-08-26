package com.shcj.cache.datamodel;

import java.util.List;

/**
 * 散点主题的相关性分析：皮尔逊相关系数 + 最小二乘拟合线。
 *
 * <p>散点图不给量化结论等于把判断相关性的活丢回给读图的人，而人眼判断相关性极不可靠——
 * 同一张图不同人能看出相反的趋势。既然问题是量化的（「key 大小对 QPS 有没有影响」），
 * 答案就该是量化的。
 *
 * <p>样本量少时相关系数极不稳定：n=5 时纯随机数据也常能算出 |r|&gt;0.7。
 * 因此 {@link #isReliable()} 会把样本量门槛带出去，由调用方决定是否展示系数。
 */
public final class Correlation {

    /** 低于这个样本量不给相关系数——太容易从噪音里算出高相关 */
    public static final int MIN_RELIABLE_SAMPLES = 15;

    /** |r| 低于此值视为没有明显相关 */
    public static final double WEAK_THRESHOLD = 0.3D;

    /** |r| 达到此值视为强相关 */
    public static final double STRONG_THRESHOLD = 0.7D;

    private final int sampleCount;
    private final double coefficient;
    private final double slope;
    private final double intercept;
    private final double minX;
    private final double maxX;

    private Correlation(int sampleCount, double coefficient, double slope, double intercept,
                        double minX, double maxX) {
        this.sampleCount = sampleCount;
        this.coefficient = coefficient;
        this.slope = slope;
        this.intercept = intercept;
        this.minX = minX;
        this.maxX = maxX;
    }

    /**
     * @param points 每项为 {x, y}
     * @return 少于 2 个点或 x 全部相同时返回 null
     */
    public static Correlation of(List<double[]> points) {
        if (points == null || points.size() < 2) {
            return null;
        }
        int n = points.size();
        double sumX = 0, sumY = 0;
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        for (double[] p : points) {
            sumX += p[0];
            sumY += p[1];
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double covariance = 0;
        double varianceX = 0;
        double varianceY = 0;
        for (double[] p : points) {
            double dx = p[0] - meanX;
            double dy = p[1] - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }
        if (varianceX == 0) {
            // x 全部相同，既算不出斜率也算不出相关
            return null;
        }
        // y 全部相同时相关系数无定义（分母为 0），按「无相关」处理，斜率仍为 0
        double coefficient = varianceY == 0 ? 0D : covariance / Math.sqrt(varianceX * varianceY);
        double slope = covariance / varianceX;
        return new Correlation(n, coefficient, slope, meanY - slope * meanX, minX, maxX);
    }

    /** 样本量是否足以让相关系数有意义 */
    public boolean isReliable() {
        return sampleCount >= MIN_RELIABLE_SAMPLES;
    }

    /** NONE / WEAK / MODERATE / STRONG */
    public String strength() {
        double abs = Math.abs(coefficient);
        if (abs < WEAK_THRESHOLD) {
            return "NONE";
        }
        if (abs >= STRONG_THRESHOLD) {
            return "STRONG";
        }
        return "MODERATE";
    }

    /** 拟合线的两个端点，直接喂给前端画线：{{x1,y1},{x2,y2}} */
    public double[][] linePoints() {
        return new double[][]{
                {minX, intercept + slope * minX},
                {maxX, intercept + slope * maxX}
        };
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public double getCoefficient() {
        return coefficient;
    }

    public double getSlope() {
        return slope;
    }

    public double getIntercept() {
        return intercept;
    }
}
