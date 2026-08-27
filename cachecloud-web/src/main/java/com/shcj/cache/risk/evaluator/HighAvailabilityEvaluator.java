package com.shcj.cache.risk.evaluator;

import com.shcj.cache.constant.InstanceStatusEnum;
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
        // 哨兵不在 context.getInstances() 里（引擎按数据节点过滤过），
        // 必须从单独的 sentinelInstances 取，否则计数恒为 0。
        // 上游取的是 status in (0,1)，即"在册未下线"，因此这里是注册总数；
        // 存活数要按状态再筛一次——多数派算的是"存活 / 注册总数"。
        List<InstanceInfo> sentinels = context.getSentinelInstances() == null
                ? new ArrayList<InstanceInfo>() : context.getSentinelInstances();
        int sentinelTotal = sentinels.size();
        int sentinelAlive = 0;
        for (InstanceInfo sentinel : sentinels) {
            if (sentinel.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                sentinelAlive++;
            }
        }
        for (InstanceInfo instance : instances) {
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
        int masterAlive = 0;
        for (InstanceInfo master : masters) {
            String hostPort = master.getIp() + ":" + master.getPort();
            boolean covered = master.getId() != null && hasSlave.contains(master.getId());
            boolean alive = master.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus();
            if (alive) {
                masterAlive++;
            }
            detail.put(hostPort, (alive ? "运行中" : "异常") + " / " + (covered ? "有从节点" : "无从节点"));
            if (!covered) {
                lonely.add(hostPort);
            }
        }

        int appType = context.getAppDesc() == null ? 0 : context.getAppDesc().getType();
        boolean standalone = appType == ConstUtils.CACHE_REDIS_STANDALONE;
        boolean sentinelApp = appType == ConstUtils.CACHE_REDIS_SENTINEL;
        boolean clusterApp = appType == ConstUtils.CACHE_TYPE_REDIS_CLUSTER;

        // 多数派 = 总数/2 + 1，即「存活数大于总数的半数」。整数下两种写法等价：
        // 3 个需存活 2 个，4 个需 3 个，5 个需 3 个。
        int masterTotal = masters.size();
        int masterQuorum = masterTotal > 0 ? masterTotal / 2 + 1 : 0;
        int sentinelQuorum = sentinelTotal > 0 ? sentinelTotal / 2 + 1 : 0;

        // 仲裁主体因架构而异：cluster 由 master 之间投票，sentinel 由哨兵投票。
        // 达不到多数派时，故障转移不会触发，集群实际上已不具备高可用能力。
        boolean clusterQuorumLost = clusterApp && masterTotal > 0 && masterAlive < masterQuorum;
        boolean sentinelQuorumLost = sentinelApp && sentinelTotal > 0 && sentinelAlive < sentinelQuorum;

        DimensionResult result;
        if (!lonely.isEmpty()) {
            String reason = standalone && masters.size() == 1
                    ? "单实例（standalone）部署，本身不具备高可用能力"
                    : String.format("%d 个主节点没有从节点：%s", lonely.size(), join(lonely));
            // 主从拓扑缺失是最高级别：节点一挂就是不可恢复的服务能力丢失
            result = DimensionResult.of(dimension(), RiskLevel.SEVERE, reason);
            result.setSuggestion(standalone && masters.size() == 1
                    ? "为该实例增加从节点，或迁移到 sentinel / cluster 架构以获得故障自动转移能力"
                    : "为上述主节点各配置至少一个从节点，避免单点故障导致分片不可用");
        } else if (clusterQuorumLost) {
            // Cluster 模式：master 之间投票判定故障与转移，存活 master 不过半就无法仲裁
            result = DimensionResult.of(dimension(), RiskLevel.RISK,
                    String.format("Cluster 模式：存活 master %d/%d，未超过半数（需 ≥ %d），"
                                    + "集群无法完成故障判定与转移", masterAlive, masterTotal, masterQuorum));
            result.setSuggestion(String.format("恢复故障 master，使存活数不少于 %d 个", masterQuorum));
        } else if (sentinelQuorumLost) {
            // Sentinel 模式：由哨兵投票选主，存活哨兵不过半则故障转移不会触发
            result = DimensionResult.of(dimension(), RiskLevel.RISK,
                    String.format("Sentinel 模式：存活哨兵 %d/%d，未超过半数（需 ≥ %d），"
                                    + "故障转移无法触发", sentinelAlive, sentinelTotal, sentinelQuorum));
            result.setSuggestion(String.format("恢复故障哨兵或补充节点，使存活哨兵不少于 %d 个", sentinelQuorum));
        } else {
            result = normal("当前集群具备高可用性 —— " + describeBasis(
                    clusterApp, sentinelApp, masterAlive, masterTotal, masterQuorum,
                    sentinelAlive, sentinelTotal, sentinelQuorum));
        }
        result.setActualValue((double) lonely.size());
        result.setSampleCount(masters.size());
        result.evidence("masters", detail);
        result.evidence("masterAlive", masterAlive);
        result.evidence("masterTotal", masterTotal);
        if (clusterApp) {
            result.evidence("masterQuorum", masterQuorum);
        }
        if (sentinelTotal > 0) {
            result.evidence("sentinelAlive", sentinelAlive);
            result.evidence("sentinelTotal", sentinelTotal);
            result.evidence("sentinelQuorum", sentinelQuorum);
        }
        return result;
    }

    /** 说明本次判定依据的是哪条规则，避免"正常"二字看不出凭什么正常 */
    private String describeBasis(boolean clusterApp, boolean sentinelApp,
                                 int masterAlive, int masterTotal, int masterQuorum,
                                 int sentinelAlive, int sentinelTotal, int sentinelQuorum) {
        if (clusterApp) {
            return String.format("Cluster 模式，存活 master %d/%d 超过半数（门槛 %d），主节点均配有从节点",
                    masterAlive, masterTotal, masterQuorum);
        }
        if (sentinelApp) {
            return String.format("Sentinel 模式，存活哨兵 %d/%d 超过半数（门槛 %d），主节点均配有从节点",
                    sentinelAlive, sentinelTotal, sentinelQuorum);
        }
        return String.format("%d 个主节点均配有从节点", masterTotal);
    }

    private String join(List<String> items) {
        if (items.size() <= 3) {
            return StringUtils.join(items, "、");
        }
        return StringUtils.join(items.subList(0, 3), "、") + " 等 " + items.size() + " 个";
    }
}
