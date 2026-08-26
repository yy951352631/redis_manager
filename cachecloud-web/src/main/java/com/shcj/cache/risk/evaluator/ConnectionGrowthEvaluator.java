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
 * 连接增长率：拟合窗口内 connected_clients 的日均增长，取增长最快的实例。
 *
 * <p>连接数缓慢爬升几乎总是客户端侧的问题——连接池只借不还、每次请求新建连接、
 * 或应用扩容后没有调整池大小。它的危害有滞后性：连接数本身不占多少资源，
 * 直到触及 maxclients 那一刻，新连接被全部拒绝，故障是断崖式的。
 *
 * <p>因此这个维度的价值全在「提前」两个字上，判定的是趋势而不是当前值——
 * 当前值由「客户端连接数」维度负责。
 *
 * <p>连接数样本量小、抖动大（一次发布就能让曲线跳一截），所以只在有明确单调趋势时
 * 才有参考意义；证据里附上首尾值，便于人工确认是真爬升还是一次阶跃。
 */
@Component
public class ConnectionGrowthEvaluator extends AbstractGrowthEvaluator {

    /** 连接数太少时百分比增长没有意义：2 个涨到 4 个也是 100% */
    private static final double MIN_MEAN_CONNECTIONS = 10D;

    @Override
    public RiskDimension dimension() {
        return RiskDimension.CONNECTION_GROWTH;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        Map<Long, List<InstanceRiskMetric>> byInstance = metricsByInstance(context);
        if (byInstance == null || byInstance.isEmpty()) {
            return insufficient("窗口内没有采集到指标样本");
        }

        GrowthTrend worstTrend = null;
        InstanceRiskMetric worstMetric = null;
        int fitted = 0;
        int skippedTooFew = 0;
        List<Map<String, Object>> detail = new ArrayList<Map<String, Object>>();

        for (List<InstanceRiskMetric> metrics : byInstance.values()) {
            List<double[]> series = seriesOf(metrics,
                    m -> m.getConnectedClients() >= 0 ? m.getConnectedClients() : -1D);
            GrowthTrend trend = GrowthTrend.fit(series);
            if (trend == null) {
                continue;
            }
            if (trend.getMeanValue() < MIN_MEAN_CONNECTIONS) {
                skippedTooFew++;
                continue;
            }
            fitted++;
            InstanceRiskMetric latest = metrics.get(metrics.size() - 1);

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("instance", latest.getIp() + ":" + latest.getPort());
            row.put("dailyPercent", round(trend.dailyPercent()));
            row.put("dailyGrowth", round(trend.getSlopePerDay()));
            row.put("firstConnections", round(trend.getFirstValue()));
            row.put("lastConnections", round(trend.getLastValue()));
            row.put("samples", trend.getSampleCount());
            detail.add(row);

            if (worstTrend == null || trend.dailyPercent() > worstTrend.dailyPercent()) {
                worstTrend = trend;
                worstMetric = latest;
            }
        }

        if (worstTrend == null) {
            return insufficient(skippedTooFew > 0
                    ? "窗口内实例平均连接数不足 " + (long) MIN_MEAN_CONNECTIONS + "，增长率无参考意义"
                    : "窗口内每个实例的有效样本都不足 " + GrowthTrend.MIN_SAMPLES + " 个，无法拟合增长趋势");
        }

        double dailyPercent = worstTrend.dailyPercent();
        LevelHit hit = judge(context, null, dailyPercent);
        DimensionResult result = build(dimension(), hit, dailyPercent, String.format(
                "%s 连接数日均增长 %.2f%%（%.0f → %.0f，窗口 %.1f 天）",
                worstMetric.getIp() + ":" + worstMetric.getPort(), dailyPercent,
                worstTrend.getFirstValue(), worstTrend.getLastValue(), worstTrend.getWindowDays()));
        result.setSampleCount(fitted);
        result.evidence("byInstance", detail);
        return result;
    }
}
