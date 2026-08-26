package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存增长率：拟合窗口内 used_memory 的日均增长，取增长最快的实例。
 *
 * <p>与「内存使用率」是互补关系，不是重复：使用率回答「现在危不危险」，
 * 增长率回答「照这个势头还有多久危险」。一个 30% 使用率但每天涨 15% 的实例，
 * 使用率维度看不出任何问题，却会在一周内打满。
 *
 * <p>取最快的实例而非平均——内存打满是单点事件，平均值会把它稀释掉。
 *
 * <p>证据里附「预计打满天数」：这是运维真正要的那个数，但不拿它定级——
 * 它在增长率为负或已超限时无定义，做阈值判定不稳。
 */
@Component
public class MemoryGrowthEvaluator extends AbstractGrowthEvaluator {

    @Override
    public RiskDimension dimension() {
        return RiskDimension.MEMORY_GROWTH;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        Map<Long, List<InstanceRiskMetric>> byInstance = metricsByInstance(context);
        if (byInstance == null || byInstance.isEmpty()) {
            return insufficient("窗口内没有采集到指标样本");
        }

        GrowthTrend worstTrend = null;
        InstanceRiskMetric worstMetric = null;
        Double worstDaysToFull = null;
        int fitted = 0;
        List<Map<String, Object>> detail = new ArrayList<Map<String, Object>>();

        for (List<InstanceRiskMetric> metrics : byInstance.values()) {
            List<double[]> series = seriesOf(metrics, m -> m.getUsedMemory() > 0 ? m.getUsedMemory() : -1D);
            GrowthTrend trend = GrowthTrend.fit(series);
            if (trend == null) {
                continue;
            }
            fitted++;
            InstanceRiskMetric latest = metrics.get(metrics.size() - 1);
            // 有 maxmemory 用它，否则退回机器总内存——与内存使用率维度同一口径
            long capacity = latest.getMaxMemory() > 0 ? latest.getMaxMemory() : latest.getTotalSystemMemory();
            Double daysToFull = trend.daysToReach(capacity);

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("instance", latest.getIp() + ":" + latest.getPort());
            row.put("dailyPercent", round(trend.dailyPercent()));
            row.put("dailyGrowthMB", round(trend.getSlopePerDay() / 1024 / 1024));
            row.put("usedMemoryMB", latest.getUsedMemory() / 1024 / 1024);
            row.put("capacityMB", capacity / 1024 / 1024);
            row.put("daysToFull", daysToFull == null ? null : round(daysToFull));
            row.put("samples", trend.getSampleCount());
            detail.add(row);

            if (worstTrend == null || trend.dailyPercent() > worstTrend.dailyPercent()) {
                worstTrend = trend;
                worstMetric = latest;
                worstDaysToFull = daysToFull;
            }
        }

        if (worstTrend == null) {
            return insufficient("窗口内每个实例的有效样本都不足 " + GrowthTrend.MIN_SAMPLES
                    + " 个，无法拟合增长趋势");
        }

        double dailyPercent = worstTrend.dailyPercent();
        LevelHit hit = judge(context, null, dailyPercent);
        StringBuilder summary = new StringBuilder(String.format(
                "%s 内存日均增长 %.2f%%（约 %.1f MB/天，窗口 %.1f 天）",
                worstMetric.getIp() + ":" + worstMetric.getPort(), dailyPercent,
                worstTrend.getSlopePerDay() / 1024 / 1024, worstTrend.getWindowDays()));
        if (worstDaysToFull != null) {
            summary.append(String.format("，按此速度约 %.1f 天后触及上限", worstDaysToFull));
        }

        DimensionResult result = build(dimension(), hit, dailyPercent, summary.toString());
        result.setSampleCount(fitted);
        result.evidence("byInstance", detail);
        if (worstDaysToFull != null) {
            result.evidence("daysToFull", round(worstDaysToFull));
        }
        return result;
    }
}
