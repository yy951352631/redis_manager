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
 * AOF 刷盘延迟次数：窗口内增量。
 *
 * <p>aof_delayed_fsync 累计，取首尾差值。该值增长意味着主线程在等待 fsync 完成，
 * 通常指向磁盘 IO 能力不足。
 */
@Component
public class AofFsyncDelayEvaluator extends AbstractDimensionEvaluator {

    @Override
    public RiskDimension dimension() {
        return RiskDimension.AOF_FSYNC_DELAY;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        long total = 0L;
        int sampleCount = 0;
        Map<String, Object> detail = new LinkedHashMap<>();

        for (List<InstanceRiskMetric> list : context.getMetricsByInstance().values()) {
            if (list.size() < 2) {
                continue;
            }
            sampleCount += list.size();
            long delta = list.get(list.size() - 1).getAofDelayedFsync() - list.get(0).getAofDelayedFsync();
            if (delta <= 0) {
                continue;
            }
            total += delta;
            InstanceRiskMetric last = list.get(list.size() - 1);
            detail.put(last.getIp() + ":" + last.getPort(), delta);
        }

        if (sampleCount == 0) {
            return insufficient("窗口内样本不足两个采集点，无法计算增量");
        }

        LevelHit hit = judge(context, null, total);
        DimensionResult result = build(dimension(), hit, (double) total,
                total == 0 ? "窗口内无 AOF 刷盘延迟" : "窗口内 AOF 刷盘延迟 " + total + " 次");
        result.setSampleCount(sampleCount);
        result.evidence("byInstance", detail);
        return result;
    }
}
