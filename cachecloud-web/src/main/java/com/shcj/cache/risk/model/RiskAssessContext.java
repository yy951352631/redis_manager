package com.shcj.cache.risk.model;

import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceCommandLatency;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.entity.RiskAssessRule;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次评估的输入上下文。
 *
 * <p>所有数据一次性装配好再交给各维度，避免 15 个维度各自查库导致重复扫描。
 */
@Data
public class RiskAssessContext {

    private AppDesc appDesc;

    /** 在线实例（已排除 stop 状态） */
    private List<InstanceInfo> instances = new ArrayList<>();

    /** 采集失败的实例，报告中显式标注，且不参与倾斜判定 */
    private List<InstanceInfo> uncollectedInstances = new ArrayList<>();

    /**
     * 哨兵节点，单独存放。
     *
     * <p>instances 刻意不含哨兵——它不承载数据，参与指标类维度只会稀释统计。
     * 但高可用性维度需要数哨兵个数判断能否仲裁，故单列一份。</p>
     */
    private List<InstanceInfo> sentinelInstances = new ArrayList<>();

    /** 评估窗口 */
    private int windowHours;
    private Date windowStart;
    private Date windowEnd;
    private long beginCollectTime;
    private long endCollectTime;

    /** 窗口内的窄表样本，按实例分组 */
    private Map<Long, List<InstanceRiskMetric>> metricsByInstance = new HashMap<>();

    /** 同一采集时刻的全实例快照，倾斜判定专用 */
    private List<InstanceRiskMetric> latestSnapshot = new ArrayList<>();

    /** 窗口内命令耗时样本（第二批维度使用） */
    private List<InstanceCommandLatency> commandSamples = new ArrayList<>();

    /** 窗口内的命令调用次数增量：instanceId -> command -> 次数（来自 instance_minute_stats） */
    private Map<Long, Map<String, Long>> commandCounts = new HashMap<>();

    /** 慢日志：instanceId -> 耗时列表（微秒） */
    private Map<Long, List<Long>> slowLogCosts = new HashMap<>();
    /** 慢日志明细摘要，展示 Top N */
    private List<Map<String, Object>> slowLogTop = new ArrayList<>();
    /** 实例的 slowlog-log-slower-than 配置：条数不可跨实例比较，必须连配置一起展示 */
    private Map<Long, Long> slowLogThresholdMicros = new HashMap<>();

    /** 阈值规则：dimension -> subItem -> level -> rule */
    private Map<String, Map<String, Map<String, RiskAssessRule>>> rules = new HashMap<>();

    /** 窄表中该集群最早的样本时间，用于判断历史是否够窗口 */
    private Long earliestMetricTime;

    /**
     * 实例运行时配置（CONFIG GET *）的按需缓存。
     *
     * <p>见类注释的约定：维度不自己取数。密码强度和持久化两个维度都要读配置，
     * 各自调一次就是每个实例两趟 CONFIG GET *（各带一次建连和 AUTH），
     * 批量评估时再按集群数放大。这里按 instanceId 缓存，一次评估内每个实例只取一次。
     */
    private RedisConfigLoader configLoader;

    private Map<Integer, Map<String, String>> configCache = new HashMap<>();

    /**
     * 取实例的运行时配置，取不到返回 null。
     *
     * <p>失败结果同样入缓存：不可达的节点不该被第二个维度再探测一次，
     * 那只会让本就超时的评估再多等一个连接超时。
     */
    public Map<String, String> getInstanceConfig(int instanceId) {
        if (configCache.containsKey(instanceId)) {
            return configCache.get(instanceId);
        }
        Map<String, String> config = null;
        if (configLoader != null) {
            try {
                config = configLoader.load(instanceId);
            } catch (Exception e) {
                config = null;
            }
        }
        configCache.put(instanceId, config);
        return config;
    }

    /** 由引擎注入，避免上下文直接依赖 RedisCenter。 */
    public interface RedisConfigLoader {
        Map<String, String> load(int instanceId) throws Exception;
    }

    public boolean isCluster() {
        return appDesc != null && com.shcj.cache.util.TypeUtil.isRedisCluster(appDesc.getType());
    }

    public List<InstanceRiskMetric> metricsOf(long instanceId) {
        List<InstanceRiskMetric> list = metricsByInstance.get(instanceId);
        return list == null ? Collections.<InstanceRiskMetric>emptyList() : list;
    }

    /** 取某维度某等级的规则，subItem 为空时传 null */
    public RiskAssessRule rule(RiskDimension dimension, String subItem, RiskLevel level) {
        Map<String, Map<String, RiskAssessRule>> byDim = rules.get(dimension.name());
        if (byDim == null) {
            return null;
        }
        Map<String, RiskAssessRule> byLevel = byDim.get(subItem == null ? "" : subItem);
        return byLevel == null ? null : byLevel.get(level.name());
    }

    /** 该实例是否有窗口内样本 */
    public boolean hasMetrics() {
        for (List<InstanceRiskMetric> list : metricsByInstance.values()) {
            if (!list.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
