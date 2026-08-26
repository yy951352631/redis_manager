package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * key 过期设置分析：统计未设置 TTL 的 key 占比。
 *
 * <p>把 Redis 当缓存用却大量不设过期时间，内存只增不减，最终要么触发驱逐要么 OOM——
 * 这是最常见也最容易被忽视的一类隐患。
 */
@Component
public class KeyExpireEvaluator extends AbstractDimensionEvaluator {

    /** key 太少时占比没有参考价值 */
    private static final long MIN_KEYS = 1000L;

    @Override
    public RiskDimension dimension() {
        return RiskDimension.KEY_EXPIRE;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        if (context.getLatestSnapshot().isEmpty()) {
            return insufficient("窗口内没有采集到指标样本");
        }
        long keys = 0L;
        long expires = 0L;
        Map<String, Object> detail = new LinkedHashMap<>();
        for (InstanceRiskMetric metric : context.getLatestSnapshot()) {
            // slave 是 master 的副本，key 会被重复计入，只统计 master
            if (metric.getRole() != 1) {
                continue;
            }
            keys += metric.getKeysCount();
            expires += metric.getExpiresCount();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("keys", metric.getKeysCount());
            row.put("expires", metric.getExpiresCount());
            row.put("avgTtlMs", metric.getAvgTtl());
            detail.put(metric.getIp() + ":" + metric.getPort(), row);
        }

        if (keys < MIN_KEYS) {
            return insufficient("master 上仅 " + keys + " 个 key，不足 " + MIN_KEYS + " 个，过期设置占比无参考意义");
        }

        double noExpirePercent = (keys - expires) * 100.0D / keys;
        LevelHit hit = judge(context, null, noExpirePercent);
        DimensionResult result = build(dimension(), hit, noExpirePercent,
                String.format("未设置过期时间的 key 占比 %.2f%%（共 %d 个 key，%d 个有 TTL）",
                        noExpirePercent, keys, expires));
        result.setSampleCount(context.getLatestSnapshot().size());
        result.evidence("byMaster", detail);
        return result;
    }
}
