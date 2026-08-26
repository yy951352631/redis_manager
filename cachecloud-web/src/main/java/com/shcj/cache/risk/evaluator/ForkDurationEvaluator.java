package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * fork 子进程耗时：取窗口内所有样本的最大值。
 *
 * <p>INFO 给的 latest_fork_usec 是"最近一次"的值，单看最新快照会漏掉窗口内更早发生的
 * 一次超长 fork——而 fork 期间主线程是阻塞的，那一次卡顿才是真正的风险。
 */
@Component
public class ForkDurationEvaluator extends AbstractDimensionEvaluator {

    @Override
    public RiskDimension dimension() {
        return RiskDimension.FORK_DURATION;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        long maxUsec = 0L;
        InstanceRiskMetric worst = null;
        int sampleCount = 0;
        Map<String, Object> detail = new LinkedHashMap<>();

        for (List<InstanceRiskMetric> list : context.getMetricsByInstance().values()) {
            sampleCount += list.size();
            for (InstanceRiskMetric metric : list) {
                if (metric.getLatestForkUsec() > maxUsec) {
                    maxUsec = metric.getLatestForkUsec();
                    worst = metric;
                }
            }
        }
        if (sampleCount == 0) {
            return insufficient("窗口内没有采集到指标样本");
        }
        if (worst == null || maxUsec <= 0) {
            DimensionResult result = normal("窗口内未发生 fork");
            result.setActualValue(0D);
            result.setSampleCount(sampleCount);
            return result;
        }
        detail.put("instance", worst.getIp() + ":" + worst.getPort());
        detail.put("forkUsec", maxUsec);

        LevelHit hit = judge(context, null, maxUsec);
        DimensionResult result = build(dimension(), hit, (double) maxUsec,
                String.format("窗口内最长 fork 耗时 %.1f ms，实例 %s:%d",
                        maxUsec / 1000.0D, worst.getIp(), worst.getPort()));
        result.setInstanceId(worst.getInstanceId());
        result.setInstanceHostPort(worst.getIp() + ":" + worst.getPort());
        result.setSampleCount(sampleCount);
        result.evidence("worst", detail);
        return result;
    }
}
