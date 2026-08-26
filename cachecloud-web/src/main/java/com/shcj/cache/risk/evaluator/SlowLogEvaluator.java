package com.shcj.cache.risk.evaluator;

import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 慢日志：二维判定，取两轴最差。
 *
 * <p><b>绝对轴</b>用 P99 耗时——耗时的绝对值有物理意义，不受配置差异影响。
 * <b>相对轴</b>本应比对自身历史基线，但慢日志表没有按天聚合的基线，第一批先只做绝对轴，
 * 相对轴在第二批随基线能力一起补。
 *
 * <p>条数<b>不能</b>跨实例比较：完全取决于各实例的 {@code slowlog-log-slower-than}。
 * 设成 10s 的实例常年零条，那不是健康而是阈值形同虚设，因此该配置本身也要作为证据展示，
 * 高得离谱时单独提示。
 */
@Component
public class SlowLogEvaluator extends AbstractDimensionEvaluator {

    /** slowlog 阈值高于该值（微秒）时，认为慢日志基本失效 */
    private static final long USELESS_THRESHOLD_MICROS = 1000_000L;

    @Override
    public RiskDimension dimension() {
        return RiskDimension.SLOW_LOG;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        List<Long> all = new ArrayList<>();
        for (List<Long> costs : context.getSlowLogCosts().values()) {
            all.addAll(costs);
        }

        List<String> uselessInstances = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : context.getSlowLogThresholdMicros().entrySet()) {
            if (entry.getValue() != null && entry.getValue() >= USELESS_THRESHOLD_MICROS) {
                uselessInstances.add(String.valueOf(entry.getKey()));
            }
        }

        if (all.isEmpty()) {
            DimensionResult result = normal("窗口内无慢日志");
            result.setSampleCount(0);
            result.evidence("slowlogThresholdMicros", context.getSlowLogThresholdMicros());
            if (!uselessInstances.isEmpty()) {
                // 零条 + 阈值过高 = 大概率没在记录，而不是真的没有慢查询
                result.setLevel(RiskLevel.ATTENTION);
                result.setSummary("窗口内无慢日志，但有 " + uselessInstances.size()
                        + " 个实例的 slowlog-log-slower-than ≥ 1s，慢查询很可能未被记录");
                result.setSuggestion("将 slowlog-log-slower-than 调整到 10000（10ms）量级，使慢日志真正生效");
                result.evidence("uselessThresholdInstanceIds", uselessInstances);
            }
            return result;
        }

        Collections.sort(all);
        long p99 = all.get(Math.min(all.size() - 1, (int) Math.ceil(all.size() * 0.99) - 1));
        long max = all.get(all.size() - 1);

        LevelHit hit = judge(context, null, p99 / 1000.0D);
        DimensionResult result = build(dimension(), hit, p99 / 1000.0D,
                String.format("窗口内慢日志 %d 条，P99 %.1f ms，最大 %.1f ms", all.size(), p99 / 1000.0D, max / 1000.0D));
        result.setSampleCount(all.size());
        result.evidence("count", all.size());
        result.evidence("p99Ms", Math.round(p99 / 1000.0D * 10) / 10.0D);
        result.evidence("maxMs", Math.round(max / 1000.0D * 10) / 10.0D);
        result.evidence("slowlogThresholdMicros", context.getSlowLogThresholdMicros());
        result.evidence("top", context.getSlowLogTop());
        if (!uselessInstances.isEmpty()) {
            result.evidence("uselessThresholdInstanceIds", uselessInstances);
        }
        return result;
    }
}
