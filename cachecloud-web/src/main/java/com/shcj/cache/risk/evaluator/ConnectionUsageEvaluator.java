package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端连接数：取最新快照中连接数最高的实例。
 */
@Component
public class ConnectionUsageEvaluator extends AbstractDimensionEvaluator {

    @Override
    public RiskDimension dimension() {
        return RiskDimension.CONNECTION_USAGE;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        if (context.getLatestSnapshot().isEmpty()) {
            return insufficient("窗口内没有采集到指标样本");
        }
        InstanceRiskMetric worst = null;
        Map<String, Object> detail = new LinkedHashMap<>();
        for (InstanceRiskMetric metric : context.getLatestSnapshot()) {
            detail.put(metric.getIp() + ":" + metric.getPort(), metric.getConnectedClients());
            if (worst == null || metric.getConnectedClients() > worst.getConnectedClients()) {
                worst = metric;
            }
        }
        LevelHit hit = judge(context, null, worst.getConnectedClients());
        DimensionResult result = build(dimension(), hit, (double) worst.getConnectedClients(),
                String.format("最高连接数 %d，实例 %s:%d", worst.getConnectedClients(), worst.getIp(), worst.getPort()));
        result.setInstanceId(worst.getInstanceId());
        result.setInstanceHostPort(worst.getIp() + ":" + worst.getPort());
        result.setSampleCount(context.getLatestSnapshot().size());
        result.evidence("instances", detail);
        return result;
    }
}
