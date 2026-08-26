package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceCommandLatency;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.service.CommandLatencyBaselineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令执行耗时：把本集群各命令的平均耗时与「同架构、同大版本的平台基线」相比。
 *
 * <p>不用绝对阈值定级。一条 GET 花 200μs 是快是慢，取决于机器和版本——
 * 在 ARM 上正常、在高主频 X86 上就偏慢。用平台自身同类实例的实测值做基线，
 * 判定的才是「这个集群相对同类是否劣化」，这也是当初设计里要求按
 * X86/ARM、不同 Redis 大版本分别建模的原因。
 *
 * <p>分钟样本存的是累计值，本维度对同一 instance+command 取窗口首尾差分，
 * 得到窗口内的真实平均耗时；相邻样本为负说明实例重启过，跳过该段。
 */
@Component
public class CommandLatencyEvaluator extends AbstractDimensionEvaluator {

    /** 命令调用次数太少时平均耗时噪声太大 */
    private static final long MIN_CALLS = 1000L;

    /** 报告里最多列出几条劣化命令 */
    private static final int TOP_N = 5;

    @Autowired
    private CommandLatencyBaselineService baselineService;

    @Override
    public RiskDimension dimension() {
        return RiskDimension.COMMAND_LATENCY;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        List<InstanceCommandLatency> samples = context.getCommandSamples();
        if (samples == null || samples.isEmpty()) {
            return insufficient("窗口内没有采集到命令耗时样本");
        }

        // instance|command -> 窗口内首尾样本
        Map<String, long[]> spans = new LinkedHashMap<String, long[]>();
        Map<String, Long> instanceOf = new LinkedHashMap<String, Long>();
        for (InstanceCommandLatency sample : samples) {
            String key = sample.getInstanceId() + "|" + sample.getCommand();
            long[] span = spans.get(key);
            if (span == null) {
                // [firstCalls, firstUsec, lastCalls, lastUsec]
                spans.put(key, new long[]{sample.getCalls(), sample.getUsec(), sample.getCalls(), sample.getUsec()});
                instanceOf.put(key, sample.getInstanceId());
            } else {
                span[2] = sample.getCalls();
                span[3] = sample.getUsec();
            }
        }

        List<Deviation> deviations = new ArrayList<Deviation>();
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        String baselineScope = null;
        int comparedCommands = 0;

        for (Map.Entry<String, long[]> entry : spans.entrySet()) {
            long[] span = entry.getValue();
            long deltaCalls = span[2] - span[0];
            long deltaUsec = span[3] - span[1];
            if (deltaCalls < MIN_CALLS || deltaUsec <= 0) {
                continue;
            }
            String command = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
            long instanceId = instanceOf.get(entry.getKey());
            double actualUsec = deltaUsec * 1.0D / deltaCalls;

            CommandLatencyBaselineService.Baseline baseline = baselineService.resolveFor(instanceId);
            if (baseline == null) {
                continue;
            }
            baselineScope = baseline.getScope();
            Double baseUsec = baseline.getAvgUsec().get(command);
            if (baseUsec == null || baseUsec <= 0) {
                continue;
            }
            comparedCommands++;
            double ratio = actualUsec / baseUsec;
            deviations.add(new Deviation(command, instanceId, actualUsec, baseUsec, ratio, deltaCalls));

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("avgUsec", round(actualUsec));
            row.put("baselineUsec", round(baseUsec));
            row.put("ratio", round(ratio));
            row.put("calls", deltaCalls);
            detail.put(instanceId + "/" + command, row);
        }

        if (comparedCommands == 0) {
            return insufficient(baselineScope == null
                    ? "平台尚未积累足够的命令耗时基线（需同架构、同大版本下至少 2 个实例、单命令 1 万次以上调用）"
                    : "窗口内没有调用量足够的命令可与基线比较");
        }

        deviations.sort(Comparator.comparingDouble((Deviation d) -> d.ratio).reversed());
        Deviation worst = deviations.get(0);

        LevelHit hit = judge(context, null, worst.ratio);
        DimensionResult result = build(dimension(), hit, worst.ratio,
                String.format("%s 平均耗时 %.0fμs，为同类基线（%s）的 %.2f 倍；共比较 %d 条命令",
                        worst.command, worst.actualUsec, baselineScope, worst.ratio, comparedCommands));
        result.setSampleCount(samples.size());
        result.evidence("baselineScope", baselineScope);
        result.evidence("topDeviations", topDeviations(deviations));
        result.evidence("byInstanceCommand", detail);
        return result;
    }

    private List<Map<String, Object>> topDeviations(List<Deviation> deviations) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Deviation d : deviations.subList(0, Math.min(TOP_N, deviations.size()))) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("command", d.command);
            row.put("instanceId", d.instanceId);
            row.put("avgUsec", round(d.actualUsec));
            row.put("baselineUsec", round(d.baseUsec));
            row.put("ratio", round(d.ratio));
            row.put("calls", d.calls);
            list.add(row);
        }
        return list;
    }

    private double round(double value) {
        return Math.round(value * 100) / 100.0D;
    }

    private static final class Deviation {
        final String command;
        final long instanceId;
        final double actualUsec;
        final double baseUsec;
        final double ratio;
        final long calls;

        Deviation(String command, long instanceId, double actualUsec, double baseUsec, double ratio, long calls) {
            this.command = command;
            this.instanceId = instanceId;
            this.actualUsec = actualUsec;
            this.baseUsec = baseUsec;
            this.ratio = ratio;
            this.calls = calls;
        }
    }
}
