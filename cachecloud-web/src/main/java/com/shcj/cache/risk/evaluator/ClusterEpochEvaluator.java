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
 * 集群切换纪元：窗口内 cluster_my_epoch 的增长次数。
 *
 * <p>纪元每次故障转移都会递增。窗口内增长说明发生过主从切换——集群本身自愈了，
 * 但频繁切换通常指向网络抖动、节点负载过高或 cluster-node-timeout 设置过小。
 */
@Component
public class ClusterEpochEvaluator extends AbstractDimensionEvaluator {

    @Override
    public RiskDimension dimension() {
        return RiskDimension.CLUSTER_EPOCH;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        long totalChanges = 0L;
        int sampleCount = 0;
        Map<String, Object> detail = new LinkedHashMap<>();

        for (List<InstanceRiskMetric> list : context.getMetricsByInstance().values()) {
            if (list.size() < 2) {
                continue;
            }
            sampleCount += list.size();
            long changes = 0L;
            long previous = list.get(0).getClusterMyEpoch();
            for (int i = 1; i < list.size(); i++) {
                long current = list.get(i).getClusterMyEpoch();
                if (current > previous) {
                    changes++;
                }
                previous = current;
            }
            if (changes > 0) {
                InstanceRiskMetric last = list.get(list.size() - 1);
                detail.put(last.getIp() + ":" + last.getPort(), changes);
                totalChanges += changes;
            }
        }

        if (sampleCount == 0) {
            return insufficient("窗口内样本不足两个采集点，无法统计纪元变化");
        }

        LevelHit hit = judge(context, null, totalChanges);
        DimensionResult result = build(dimension(), hit, (double) totalChanges,
                totalChanges == 0 ? "窗口内未发生纪元变化，集群拓扑稳定"
                        : "窗口内检测到 " + totalChanges + " 次纪元递增（发生过故障转移）");
        result.setSampleCount(sampleCount);
        result.evidence("byInstance", detail);
        return result;
    }
}
