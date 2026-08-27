package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.entity.RiskAssessRule;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 命中率定级阶梯的边界测试。
 *
 * <p>定级是按 严重 → 风险 → 关注 取第一个命中的规则，所以阈值必须单调递减，
 * 否则轻的那一档会被重的完全遮蔽、永远不触发。历史上就出过这个问题：
 * 关注设成 50% 而风险/严重还停在 80%/60%，任何低于 50% 的值都先撞上严重，
 * 「关注」成了一条死规则。这里把当前阶梯（关注 60% / 风险 30%）的每个边界钉住。</p>
 */
public class HitRateLadderTest {

    private final HitRateEvaluator evaluator = new HitRateEvaluator();

    private RiskAssessRule rule(RiskLevel level, double threshold) {
        RiskAssessRule rule = new RiskAssessRule();
        rule.setDimension(RiskDimension.HIT_RATE.name());
        rule.setLevel(level.name());
        rule.setOperator("LT");
        rule.setThreshold(threshold);
        return rule;
    }

    /** 造一个刚好产生指定命中率的上下文：窗口首尾两条采样，总查找 1000 次 */
    private RiskAssessContext contextWithHitRate(double percent) {
        RiskAssessContext context = new RiskAssessContext();

        Map<String, RiskAssessRule> byLevel = new HashMap<>();
        byLevel.put(RiskLevel.ATTENTION.name(), rule(RiskLevel.ATTENTION, 60D));
        byLevel.put(RiskLevel.RISK.name(), rule(RiskLevel.RISK, 30D));
        Map<String, Map<String, RiskAssessRule>> bySub = new HashMap<>();
        bySub.put("", byLevel);
        Map<String, Map<String, Map<String, RiskAssessRule>>> rules = new HashMap<>();
        rules.put(RiskDimension.HIT_RATE.name(), bySub);
        context.setRules(rules);

        long hits = Math.round(percent * 10);
        long misses = 1000 - hits;
        List<InstanceRiskMetric> series = new ArrayList<>();
        series.add(metric(202608270100L, 0, 0));
        series.add(metric(202608270200L, hits, misses));
        Map<Long, List<InstanceRiskMetric>> byInstance = new HashMap<>();
        byInstance.put(13L, series);
        context.setMetricsByInstance(byInstance);
        return context;
    }

    private InstanceRiskMetric metric(long collectTime, long hits, long misses) {
        InstanceRiskMetric metric = new InstanceRiskMetric();
        metric.setInstanceId(13L);
        metric.setCollectTime(collectTime);
        metric.setKeyspaceHits(hits);
        metric.setKeyspaceMisses(misses);
        return metric;
    }

    private RiskLevel levelAt(double percent) {
        DimensionResult result = evaluator.evaluate(contextWithHitRate(percent));
        return result.getLevel();
    }

    @Test
    public void 命中率达标为正常() {
        assertEquals(RiskLevel.NORMAL, levelAt(100));
        assertEquals(RiskLevel.NORMAL, levelAt(80));
        assertEquals(RiskLevel.NORMAL, levelAt(60), "阈值是 LT，等于 60% 不算关注");
    }

    @Test
    public void 低于六十为关注() {
        assertEquals(RiskLevel.ATTENTION, levelAt(59.9));
        assertEquals(RiskLevel.ATTENTION, levelAt(45));
        assertEquals(RiskLevel.ATTENTION, levelAt(30), "等于 30% 还落在关注区间");
    }

    @Test
    public void 低于三十为风险() {
        assertEquals(RiskLevel.RISK, levelAt(29.9));
        assertEquals(RiskLevel.RISK, levelAt(10));
        assertEquals(RiskLevel.RISK, levelAt(0));
    }

    @Test
    public void 关注档没有被更重的档遮蔽() {
        // 阶梯一旦非单调，这一整段都会掉到更重的等级里去
        for (int percent = 30; percent < 60; percent++) {
            assertEquals(RiskLevel.ATTENTION, levelAt(percent),
                    "命中率 " + percent + "% 应当是关注");
        }
    }
}
