package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskLevel;
import com.shcj.cache.util.ConstUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 高可用性：主节点是否有从节点兜底。
 *
 * <p>没有从节点的主节点一旦宕机即不可恢复地丢失该分片的服务能力，
 * standalone 单实例同理。这两种情况都定为风险。</p>
 */
@Component
public class HighAvailabilityEvaluator extends AbstractDimensionEvaluator {

    @Override
    public RiskDimension dimension() {
        return RiskDimension.HIGH_AVAILABILITY;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        List<InstanceInfo> instances = context.getInstances();
        if (instances == null || instances.isEmpty()) {
            return insufficient("没有可检查的节点");
        }

        List<InstanceInfo> masters = new ArrayList<>();
        Set<Integer> hasSlave = new HashSet<>();
        int sentinelCount = 0;
        for (InstanceInfo instance : instances) {
            if (instance.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
                sentinelCount++;
                continue;
            }
            if (instance.getMasterInstanceId() > 0) {
                hasSlave.add(instance.getMasterInstanceId());
            } else {
                masters.add(instance);
            }
        }
        if (masters.isEmpty()) {
            return insufficient("未识别到主节点，无法判定高可用性");
        }

        List<String> lonely = new ArrayList<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        for (InstanceInfo master : masters) {
            String hostPort = master.getIp() + ":" + master.getPort();
            boolean covered = master.getId() != null && hasSlave.contains(master.getId());
            detail.put(hostPort, covered ? "有从节点" : "无从节点");
            if (!covered) {
                lonely.add(hostPort);
            }
        }

        boolean standalone = context.getAppDesc() != null
                && context.getAppDesc().getType() == ConstUtils.CACHE_REDIS_STANDALONE;

        DimensionResult result;
        if (!lonely.isEmpty()) {
            String reason = standalone && masters.size() == 1
                    ? "单实例（standalone）部署，本身不具备高可用能力"
                    : String.format("%d 个主节点没有从节点：%s", lonely.size(), join(lonely));
            result = DimensionResult.of(dimension(), RiskLevel.RISK, reason);
            result.setSuggestion(standalone && masters.size() == 1
                    ? "为该实例增加从节点，或迁移到 sentinel / cluster 架构以获得故障自动转移能力"
                    : "为上述主节点各配置至少一个从节点，避免单点故障导致分片不可用");
        } else {
            // 有从节点还不等于能自动切换：cluster 靠自身、sentinel 靠哨兵，
            // 主从架构若没有哨兵，故障时仍需人工介入，这一点要说清楚。
            boolean sentinelApp = context.getAppDesc() != null
                    && context.getAppDesc().getType() == ConstUtils.CACHE_REDIS_SENTINEL;
            if (sentinelApp && sentinelCount < 3) {
                result = DimensionResult.of(dimension(), RiskLevel.ATTENTION,
                        String.format("主节点均有从节点，但存活哨兵仅 %d 个，不足 3 个无法可靠仲裁", sentinelCount));
                result.setSuggestion("补足至少 3 个哨兵节点，否则故障转移可能无法达成多数派");
            } else {
                result = normal(String.format("%d 个主节点均配有从节点", masters.size()));
            }
        }
        result.setActualValue((double) lonely.size());
        result.setSampleCount(masters.size());
        result.evidence("masters", detail);
        if (sentinelCount > 0) {
            result.evidence("sentinelCount", sentinelCount);
        }
        return result;
    }

    private String join(List<String> items) {
        if (items.size() <= 3) {
            return StringUtils.join(items, "、");
        }
        return StringUtils.join(items.subList(0, 3), "、") + " 等 " + items.size() + " 个";
    }
}
