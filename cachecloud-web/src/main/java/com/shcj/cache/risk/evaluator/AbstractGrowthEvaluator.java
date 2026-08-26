package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.risk.model.RiskAssessContext;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 增长率类维度的公共部分：把 collect_time 还原成时间轴，逐实例拟合趋势。
 *
 * <p>{@code collect_time} 存的是 {@code yyyyMMddHHmm} 形式的数字，直接当数值做回归是错的——
 * 202608202359 到 202608210000 只差 1 分钟，数值上却跳了 7641。必须先还原成毫秒。
 */
abstract class AbstractGrowthEvaluator extends AbstractDimensionEvaluator {

    /** 只统计 master：slave 是副本，内存与连接会重复计入 */
    protected static final int ROLE_MASTER = 1;

    private static final String COLLECT_TIME_PATTERN = "yyyyMMddHHmm";

    /**
     * 取某个实例在窗口内的时序点。
     *
     * @param valueOf 从指标行取出要拟合的数值，返回负数表示该点不可用
     */
    protected List<double[]> seriesOf(List<InstanceRiskMetric> metrics, ValueExtractor valueOf) {
        List<double[]> points = new ArrayList<double[]>();
        if (metrics == null) {
            return points;
        }
        SimpleDateFormat format = new SimpleDateFormat(COLLECT_TIME_PATTERN);
        for (InstanceRiskMetric metric : metrics) {
            if (metric.getRole() != ROLE_MASTER) {
                continue;
            }
            double value = valueOf.apply(metric);
            if (value < 0) {
                continue;
            }
            try {
                long millis = format.parse(String.valueOf(metric.getCollectTime())).getTime();
                points.add(new double[]{millis, value});
            } catch (ParseException e) {
                // 单个异常时间戳跳过即可，不影响整体拟合
            }
        }
        points.sort((a, b) -> Double.compare(a[0], b[0]));
        return points;
    }

    /** 遍历上下文中每个实例的时序 */
    protected Map<Long, List<InstanceRiskMetric>> metricsByInstance(RiskAssessContext context) {
        return context.getMetricsByInstance();
    }

    protected double round(double value) {
        return Math.round(value * 100) / 100.0D;
    }

    interface ValueExtractor {
        double apply(InstanceRiskMetric metric);
    }
}
