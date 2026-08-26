package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存使用率：取全部在线实例中使用率最高的那个。
 *
 * <p>取最大值而非平均——内存打满是单点事件，一个实例 95% 就足以触发驱逐或 OOM，
 * 平均值会把它稀释掉。
 *
 * <p>未设置 maxmemory 的实例改以<b>机器总内存</b>（INFO Memory.total_system_memory）为分母。
 * 同时对"未设限"这件事本身扣分——没有 maxmemory 就没有淘汰兜底，内存只会一路涨到把机器吃满，
 * 这本身就是风险，不能因为算得出使用率就当作正常。扣分档位由规则表的
 * {@code CLUSTER 维度 sub_item=no_maxmemory} 控制，可调可关。
 *
 * <p>需要注意的局限：机器总内存是<b>整机</b>的量，同机部署多个实例时，单实例占比会低估整机风险
 * （4 个实例各占 25% 就已经把机器吃满）。证据里会标注每个实例的计算口径，便于人工判断。
 */
@Component
public class MemoryUsageEvaluator extends AbstractDimensionEvaluator {

    /** 未设置 maxmemory 时的扣分档，用规则表的这个子项配置 */
    private static final String SUB_NO_MAXMEMORY = "no_maxmemory";

    @Override
    public RiskDimension dimension() {
        return RiskDimension.MEMORY_USAGE;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        if (context.getLatestSnapshot().isEmpty()) {
            return insufficient("窗口内没有采集到指标样本");
        }

        double worstRatio = -1D;
        InstanceRiskMetric worst = null;
        boolean worstBySystemMemory = false;
        int unlimited = 0;
        int unmeasurable = 0;
        List<Map<String, Object>> detail = new ArrayList<>();

        for (InstanceRiskMetric metric : context.getLatestSnapshot()) {
            boolean bySystem = metric.getMaxMemory() <= 0;
            long denominator = bySystem ? metric.getTotalSystemMemory() : metric.getMaxMemory();
            if (bySystem) {
                unlimited++;
            }
            if (denominator <= 0) {
                // 既没有 maxmemory，也拿不到机器总内存，该实例无法计算
                unmeasurable++;
                continue;
            }
            double ratio = metric.getUsedMemory() * 100.0D / denominator;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("instance", metric.getIp() + ":" + metric.getPort());
            row.put("usedMemoryMB", metric.getUsedMemory() / 1024 / 1024);
            row.put("limitMB", denominator / 1024 / 1024);
            row.put("basis", bySystem ? "机器总内存（未设置 maxmemory）" : "maxmemory");
            row.put("usagePercent", Math.round(ratio * 100) / 100.0D);
            detail.add(row);

            if (ratio > worstRatio) {
                worstRatio = ratio;
                worst = metric;
                worstBySystemMemory = bySystem;
            }
        }

        if (worst == null) {
            DimensionResult result = insufficient(
                    "全部 " + unmeasurable + " 个实例既未设置 maxmemory，也无法获取机器总内存，使用率无从计算");
            result.evidence("unmeasurableInstances", unmeasurable);
            result.setSuggestion("建议为实例设置 maxmemory 与淘汰策略，否则内存增长不可控且无法预警");
            return result;
        }

        LevelHit usageHit = judge(context, null, worstRatio);
        // 「未设限实例数」单独定级，与使用率取最差——两者是独立的风险来源
        LevelHit noLimitHit = judge(context, SUB_NO_MAXMEMORY, unlimited);
        RiskLevel level = RiskLevel.worse(usageHit.level, noLimitHit.level);

        StringBuilder summary = new StringBuilder(String.format("最高内存使用率 %.2f%%（%s），实例 %s:%d",
                worstRatio, worstBySystemMemory ? "以机器总内存计" : "以 maxmemory 计",
                worst.getIp(), worst.getPort()));
        if (unlimited > 0) {
            summary.append("；").append(unlimited).append(" 个实例未设置 maxmemory，无淘汰兜底");
        }

        DimensionResult result = DimensionResult.of(dimension(), level, summary.toString());
        result.setActualValue(Math.round(worstRatio * 100) / 100.0D);
        result.setThreshold(usageHit.threshold);
        result.setInstanceId(worst.getInstanceId());
        result.setInstanceHostPort(worst.getIp() + ":" + worst.getPort());
        result.setSampleCount(context.getLatestSnapshot().size());
        result.setSuggestion(pickSuggestion(usageHit, noLimitHit, level, unlimited));
        result.evidence("instances", detail);
        result.evidence("unlimitedInstances", unlimited);
        if (unmeasurable > 0) {
            result.evidence("unmeasurableInstances", unmeasurable);
        }
        if (unlimited > 0) {
            result.evidence("note", "未设置 maxmemory 的实例以机器总内存为分母；同机多实例时该占比会低估整机风险");
        }
        return result;
    }

    private String pickSuggestion(LevelHit usageHit, LevelHit noLimitHit, RiskLevel level, int unlimited) {
        if (level == noLimitHit.level && noLimitHit.suggestion != null && unlimited > 0) {
            return noLimitHit.suggestion;
        }
        if (usageHit.suggestion != null) {
            return usageHit.suggestion;
        }
        return unlimited > 0 ? "建议为实例设置 maxmemory 与淘汰策略，避免内存增长把机器吃满" : null;
    }
}
