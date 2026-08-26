package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RDB 写盘耗时：取窗口内最大值。
 *
 * <p>rdb_last_bgsave_time_sec 为 -1 表示实例启动后从未执行过 bgsave，这不是异常。
 */
@Component
public class RdbDurationEvaluator extends AbstractDimensionEvaluator {

    @Override
    public RiskDimension dimension() {
        return RiskDimension.RDB_DURATION;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        int maxSec = -1;
        InstanceRiskMetric worst = null;
        int sampleCount = 0;

        for (List<InstanceRiskMetric> list : context.getMetricsByInstance().values()) {
            sampleCount += list.size();
            for (InstanceRiskMetric metric : list) {
                if (metric.getRdbLastBgsaveTimeSec() > maxSec) {
                    maxSec = metric.getRdbLastBgsaveTimeSec();
                    worst = metric;
                }
            }
        }
        if (sampleCount == 0) {
            return insufficient("窗口内没有采集到指标样本");
        }
        if (maxSec < 0) {
            DimensionResult result = normal("窗口内未执行过 RDB 落盘");
            result.setSampleCount(sampleCount);
            return result;
        }

        LevelHit hit = judge(context, null, maxSec);
        DimensionResult result = build(dimension(), hit, (double) maxSec,
                String.format("最长 RDB 落盘耗时 %d 秒，实例 %s:%d", maxSec, worst.getIp(), worst.getPort()));
        result.setInstanceId(worst.getInstanceId());
        result.setInstanceHostPort(worst.getIp() + ":" + worst.getPort());
        result.setSampleCount(sampleCount);
        return result;
    }
}
