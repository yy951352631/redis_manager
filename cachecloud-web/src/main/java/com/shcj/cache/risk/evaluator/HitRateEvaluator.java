package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 缓存命中率：用窗口首尾样本的差值算区间命中率。
 *
 * <p>keyspace_hits/misses 是累计值，直接用最新值算出来的是实例启动至今的平均命中率，
 * 对近期变化不敏感——一个跑了 30 天、最近一小时命中率跌到 20% 的实例，累计值可能仍有 95%。
 */
@Component
public class HitRateEvaluator extends AbstractDimensionEvaluator {

    /** 区间内查询次数太少时命中率没有统计意义 */
    private static final long MIN_LOOKUPS = 100L;

    @Override
    public RiskDimension dimension() {
        return RiskDimension.HIT_RATE;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        long hits = 0L;
        long misses = 0L;
        int sampleCount = 0;

        for (Map.Entry<Long, List<InstanceRiskMetric>> entry : context.getMetricsByInstance().entrySet()) {
            List<InstanceRiskMetric> list = entry.getValue();
            if (list.size() < 2) {
                continue;
            }
            InstanceRiskMetric first = list.get(0);
            InstanceRiskMetric last = list.get(list.size() - 1);
            long deltaHits = last.getKeyspaceHits() - first.getKeyspaceHits();
            long deltaMisses = last.getKeyspaceMisses() - first.getKeyspaceMisses();
            // 实例重启会让累计计数器归零，此时差值为负，丢弃该实例的样本
            if (deltaHits < 0 || deltaMisses < 0) {
                continue;
            }
            hits += deltaHits;
            misses += deltaMisses;
            sampleCount += list.size();
        }

        long lookups = hits + misses;
        if (lookups < MIN_LOOKUPS) {
            return insufficient("窗口内读请求仅 " + lookups + " 次，不足 " + MIN_LOOKUPS + " 次，命中率无统计意义");
        }

        double hitRate = hits * 100.0D / lookups;
        LevelHit hit = judge(context, null, hitRate);
        DimensionResult result = build(dimension(), hit, hitRate,
                String.format("窗口内命中率 %.2f%%（命中 %d / 未命中 %d）", hitRate, hits, misses));
        result.setSampleCount(sampleCount);
        result.evidence("hits", hits);
        result.evidence("misses", misses);
        return result;
    }
}
