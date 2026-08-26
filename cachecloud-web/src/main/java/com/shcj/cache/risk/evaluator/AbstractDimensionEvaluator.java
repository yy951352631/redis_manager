package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.RiskAssessRule;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskDimensionEvaluator;
import com.shcj.cache.risk.model.RiskLevel;

/**
 * 维度评估器的公共部分：按 严重 → 风险 → 关注 的顺序逐档比对阈值，命中即返回。
 */
public abstract class AbstractDimensionEvaluator implements RiskDimensionEvaluator {

    private static final RiskLevel[] LEVELS_DESC = {RiskLevel.SEVERE, RiskLevel.RISK, RiskLevel.ATTENTION};

    /**
     * 按阈值定级。
     *
     * @param subItem 子项标识，无子项传 null
     * @return 命中的等级与阈值；未命中任何档时等级为 NORMAL、阈值取最低档
     */
    protected LevelHit judge(RiskAssessContext context, String subItem, double value) {
        Double lowestThreshold = null;
        for (RiskLevel level : LEVELS_DESC) {
            RiskAssessRule rule = context.rule(dimension(), subItem, level);
            if (rule == null || rule.getThreshold() == null) {
                continue;
            }
            lowestThreshold = rule.getThreshold();
            if (rule.getMinAbsolute() != null && value < rule.getMinAbsolute()) {
                // 绝对值太小，判定无意义（如 30MB 的集群倾斜 3 倍也无危害）
                continue;
            }
            if (matches(rule, value)) {
                return new LevelHit(level, rule.getThreshold(), rule.getSuggestion());
            }
        }
        return new LevelHit(RiskLevel.NORMAL, lowestThreshold, null);
    }

    private boolean matches(RiskAssessRule rule, double value) {
        double threshold = rule.getThreshold();
        String operator = rule.getOperator() == null ? "GT" : rule.getOperator();
        switch (operator) {
            case "GTE":
                return value >= threshold;
            case "LT":
                return value < threshold;
            case "LTE":
                return value <= threshold;
            case "EQ":
                return Double.compare(value, threshold) == 0;
            case "NE":
                return Double.compare(value, threshold) != 0;
            case "GT":
            default:
                return value > threshold;
        }
    }

    protected DimensionResult insufficient(String reason) {
        return DimensionResult.of(dimension(), RiskLevel.INSUFFICIENT_DATA, reason);
    }

    protected DimensionResult normal(String summary) {
        return DimensionResult.of(dimension(), RiskLevel.NORMAL, summary);
    }

    /** 便于子类少写模板代码 */
    protected DimensionResult build(RiskDimension dimension, LevelHit hit, double value, String summary) {
        DimensionResult result = DimensionResult.of(dimension, hit.level, summary);
        result.setActualValue(value);
        result.setThreshold(hit.threshold);
        result.setSuggestion(hit.suggestion);
        return result;
    }

    protected static class LevelHit {
        public final RiskLevel level;
        public final Double threshold;
        public final String suggestion;

        LevelHit(RiskLevel level, Double threshold, String suggestion) {
            this.level = level;
            this.threshold = threshold;
            this.suggestion = suggestion;
        }
    }
}
