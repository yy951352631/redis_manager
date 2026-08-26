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
 * 被拒绝的连接数：窗口内的增量。
 *
 * <p>rejected_connections 是累计值，必须取窗口首尾差值——否则一个半年前触发过一次连接打满的实例
 * 会被永远判为异常。
 */
@Component
public class RejectedConnectionEvaluator extends AbstractDimensionEvaluator {

    @Override
    public RiskDimension dimension() {
        return RiskDimension.REJECTED_CONNECTION;
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
            InstanceRiskMetric first = list.get(0);
            InstanceRiskMetric last = list.get(list.size() - 1);
            long delta = last.getRejectedConnections() - first.getRejectedConnections();
            sampleCount += list.size();
            if (delta <= 0) {
                // 负值意味着实例在窗口内重启过，计数器归零，无法得出该实例的增量
                continue;
            }
            total += delta;
            detail.put(last.getIp() + ":" + last.getPort(), delta);
        }

        if (sampleCount == 0) {
            return insufficient("窗口内样本不足两个采集点，无法计算增量");
        }

        LevelHit hit = judge(context, null, total);
        DimensionResult result = build(dimension(), hit, (double) total,
                total == 0 ? "窗口内无连接被拒绝" : "窗口内共 " + total + " 次连接被拒绝");
        result.setSampleCount(sampleCount);
        result.evidence("byInstance", detail);
        return result;
    }
}
