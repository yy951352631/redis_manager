package com.shcj.cache.risk.collect;

import com.shcj.cache.async.AsyncService;
import com.shcj.cache.async.KeyCallable;
import com.shcj.cache.constant.RedisConstant;
import com.shcj.cache.dao.InstanceCommandLatencyDao;
import com.shcj.cache.dao.InstanceRuntimeProfileDao;
import com.shcj.cache.entity.InstanceRuntimeProfile;
import com.shcj.cache.util.RedisOsArchUtil;
import com.shcj.cache.dao.InstanceRiskMetricDao;
import com.shcj.cache.entity.InstanceCommandLatency;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.async.NamedThreadFactory;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 把每分钟采集到的 INFO 顺带落一份风险评估用的窄表数据。
 *
 * <p>挂在既有采集回调里，不额外发起任何 Redis 连接；写入走异步线程池且失败只记日志，
 * 与慢日志落库同模式——评估表写失败绝不能拖垮每分钟的指标采集主流程。
 */
@Component
public class RiskMetricCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskMetricCollector.class);

    private static final String THREAD_POOL_KEY = "risk-metric-collector";

    /**
     * 命令耗时白名单。只采数据命令：一是排除采集器自身打进去的 info/cluster/ping 等运维命令，
     * 二是把「实例 × 命令 × 时间」这张高基数表的行数压到可控范围（见方案的容量测算）。
     */
    private static final Set<String> COMMAND_WHITELIST = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "get", "set", "setex", "mget", "del", "exists", "expire", "incr",
            "hget", "hset", "hgetall", "lpush", "lrange", "sadd", "zadd")));

    /** 命令耗时的采样间隔（分钟），只在分钟数为其整数倍时落库 */
    private static final int COMMAND_SAMPLE_INTERVAL_MINUTES = 5;

    @Autowired
    private AsyncService asyncService;

    @Autowired
    private InstanceRiskMetricDao instanceRiskMetricDao;

    @Autowired
    private InstanceCommandLatencyDao instanceCommandLatencyDao;

    @Autowired
    private InstanceRuntimeProfileDao instanceRuntimeProfileDao;

    @PostConstruct
    public void init() {
        // 队列满时由调用线程执行会阻塞采集主流程，这里选择丢弃并计日志：宁可丢一分钟的评估样本，
        // 也不能让评估拖慢采集。
        asyncService.assemblePool(THREAD_POOL_KEY, new ThreadPoolExecutor(
                2, 8, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(4096),
                new NamedThreadFactory(THREAD_POOL_KEY, true),
                new ThreadPoolExecutor.DiscardPolicy()));
    }

    /**
     * @param instanceInfo    实例信息，为空时跳过（拿不到 instanceId 无法落库）
     * @param infoMap         本次采集的 INFO 分段结果
     * @param clusterInfoMap  cluster info 结果，非集群实例可为空
     * @param collectTime     yyyyMMddHHmm
     */
    public void collect(final InstanceInfo instanceInfo,
                        final Map<RedisConstant, Map<String, Object>> infoMap,
                        final Map<String, Object> clusterInfoMap,
                        final long collectTime) {
        if (instanceInfo == null || instanceInfo.getId() == null || infoMap == null || infoMap.isEmpty()) {
            return;
        }
        final InstanceRiskMetric metric = buildMetric(instanceInfo, infoMap, clusterInfoMap, collectTime);
        final InstanceRuntimeProfile profile = shouldSampleCommands(collectTime)
                ? buildRuntimeProfile(instanceInfo, infoMap) : null;
        final List<InstanceCommandLatency> commands = shouldSampleCommands(collectTime)
                ? buildCommandSamples(instanceInfo, infoMap, collectTime)
                : Collections.<InstanceCommandLatency>emptyList();

        String key = THREAD_POOL_KEY + "-" + instanceInfo.getId() + "-" + collectTime;
        asyncService.submitFuture(THREAD_POOL_KEY, new KeyCallable<Boolean>(key) {
            @Override
            public Boolean execute() {
                try {
                    instanceRiskMetricDao.save(metric);
                    if (!commands.isEmpty()) {
                        instanceCommandLatencyDao.batchSaveMinute(commands);
                    }
                    if (profile != null) {
                        instanceRuntimeProfileDao.upsert(profile);
                    }
                    return true;
                } catch (Exception e) {
                    LOGGER.warn("save risk metric failed instanceId={} collectTime={}: {}",
                            instanceInfo.getId(), collectTime, e.getMessage());
                    return false;
                }
            }
        });
    }

    /**
     * 架构与版本变化极少，跟命令耗时同频（5 分钟）落一次即可，不必每分钟写。
     */
    private InstanceRuntimeProfile buildRuntimeProfile(InstanceInfo instanceInfo,
                                                       Map<RedisConstant, Map<String, Object>> infoMap) {
        Map<String, Object> server = section(infoMap, RedisConstant.Server);
        if (server.isEmpty()) {
            return null;
        }
        Map<String, Object> flat = new java.util.HashMap<String, Object>();
        flat.put("Server", server);
        String arch = RedisOsArchUtil.resolveArch(flat);
        Object versionObj = server.get("redis_version");
        String version = versionObj == null ? null : String.valueOf(versionObj);

        InstanceRuntimeProfile profile = new InstanceRuntimeProfile();
        profile.setInstanceId(instanceInfo.getId());
        profile.setAppId(instanceInfo.getAppId());
        profile.setOsArch(arch);
        profile.setRedisVersion(version);
        profile.setMajorVersion(majorVersion(version));
        profile.setUpdateTime(new java.util.Date());
        return profile;
    }

    /** 6.2.13 -> 6.2；只保留大版本，小版本对命令耗时基线没有区分意义 */
    private String majorVersion(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    private boolean shouldSampleCommands(long collectTime) {
        return collectTime % 100 % COMMAND_SAMPLE_INTERVAL_MINUTES == 0;
    }

    private InstanceRiskMetric buildMetric(InstanceInfo instanceInfo,
                                           Map<RedisConstant, Map<String, Object>> infoMap,
                                           Map<String, Object> clusterInfoMap,
                                           long collectTime) {
        Map<String, Object> memory = section(infoMap, RedisConstant.Memory);
        Map<String, Object> stats = section(infoMap, RedisConstant.Stats);
        Map<String, Object> clients = section(infoMap, RedisConstant.Clients);
        Map<String, Object> persistence = section(infoMap, RedisConstant.Persistence);
        Map<String, Object> cpu = section(infoMap, RedisConstant.CPU);
        Map<String, Object> replication = section(infoMap, RedisConstant.Replication);

        InstanceRiskMetric metric = new InstanceRiskMetric();
        metric.setCollectTime(collectTime);
        metric.setAppId(instanceInfo.getAppId());
        metric.setInstanceId(instanceInfo.getId());
        metric.setIp(instanceInfo.getIp());
        metric.setPort(instanceInfo.getPort());
        metric.setRole("slave".equals(MapUtils.getString(replication, "role")) ? 2 : 1);

        metric.setUsedMemory(MapUtils.getLongValue(memory, "used_memory", 0L));
        metric.setMaxMemory(MapUtils.getLongValue(memory, "maxmemory", 0L));
        metric.setTotalSystemMemory(MapUtils.getLongValue(memory, "total_system_memory", 0L));
        metric.setMemFragRatio(MapUtils.getDoubleValue(memory, "mem_fragmentation_ratio", 0D));

        fillKeyspace(metric, infoMap);

        metric.setConnectedClients(MapUtils.getIntValue(clients, "connected_clients", 0));
        metric.setRejectedConnections(MapUtils.getLongValue(stats, "rejected_connections", 0L));
        metric.setKeyspaceHits(MapUtils.getLongValue(stats, "keyspace_hits", 0L));
        metric.setKeyspaceMisses(MapUtils.getLongValue(stats, "keyspace_misses", 0L));
        metric.setExpiredKeys(MapUtils.getLongValue(stats, "expired_keys", 0L));
        metric.setEvictedKeys(MapUtils.getLongValue(stats, "evicted_keys", 0L));
        metric.setInstantaneousOps(MapUtils.getLongValue(stats, "instantaneous_ops_per_sec", 0L));
        metric.setNetInputBytes(MapUtils.getLongValue(stats, "total_net_input_bytes", 0L));
        metric.setNetOutputBytes(MapUtils.getLongValue(stats, "total_net_output_bytes", 0L));

        // latest_fork_usec 在不同版本里分属 Stats 与 Persistence，两处都取
        long forkUsec = MapUtils.getLongValue(stats, "latest_fork_usec", 0L);
        if (forkUsec <= 0) {
            forkUsec = MapUtils.getLongValue(persistence, "latest_fork_usec", 0L);
        }
        metric.setLatestForkUsec(forkUsec);
        metric.setRdbLastBgsaveTimeSec(MapUtils.getIntValue(persistence, "rdb_last_bgsave_time_sec", -1));
        metric.setAofDelayedFsync(MapUtils.getLongValue(persistence, "aof_delayed_fsync", 0L));

        metric.setUsedCpuSys(MapUtils.getDoubleValue(cpu, "used_cpu_sys", 0D));
        metric.setUsedCpuUser(MapUtils.getDoubleValue(cpu, "used_cpu_user", 0D));

        if (clusterInfoMap != null && !clusterInfoMap.isEmpty()) {
            metric.setClusterMyEpoch(MapUtils.getLongValue(clusterInfoMap, "cluster_my_epoch", 0L));
        }
        return metric;
    }

    /**
     * Keyspace 段形如 {@code db0: keys=1,expires=0,avg_ttl=0}，多个 db 时累加 keys/expires，
     * avg_ttl 取最大值（更保守地反映"有 TTL 的 key 平均还能活多久"）。
     */
    private void fillKeyspace(InstanceRiskMetric metric, Map<RedisConstant, Map<String, Object>> infoMap) {
        Map<String, Object> keyspace = section(infoMap, RedisConstant.Keyspace);
        long keys = 0L;
        long expires = 0L;
        long avgTtl = 0L;
        for (Map.Entry<String, Object> entry : keyspace.entrySet()) {
            String value = String.valueOf(entry.getValue());
            keys += parseKeyValue(value, "keys");
            expires += parseKeyValue(value, "expires");
            avgTtl = Math.max(avgTtl, parseKeyValue(value, "avg_ttl"));
        }
        metric.setKeysCount(keys);
        metric.setExpiresCount(expires);
        metric.setAvgTtl(avgTtl);
    }

    private long parseKeyValue(String value, String name) {
        if (StringUtils.isBlank(value)) {
            return 0L;
        }
        for (String part : value.split(",")) {
            String[] kv = part.split("=");
            if (kv.length == 2 && name.equals(kv[0].trim())) {
                try {
                    return Long.parseLong(kv[1].trim());
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    /**
     * Commandstats 段形如 {@code cmdstat_get: calls=1,usec=5,usec_per_call=5.00}。
     * 这里落 calls 与 usec 的累计值而不是 usec_per_call——后者是实例启动至今的均值，
     * 对近期劣化不敏感，必须由消费方对相邻样本做差分。
     */
    private List<InstanceCommandLatency> buildCommandSamples(InstanceInfo instanceInfo,
                                                             Map<RedisConstant, Map<String, Object>> infoMap,
                                                             long collectTime) {
        Map<String, Object> commandStats = section(infoMap, RedisConstant.Commandstats);
        if (commandStats.isEmpty()) {
            return Collections.emptyList();
        }
        List<InstanceCommandLatency> list = new ArrayList<>();
        for (Map.Entry<String, Object> entry : commandStats.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith("cmdstat_")) {
                continue;
            }
            String command = key.substring("cmdstat_".length()).toLowerCase();
            if (!COMMAND_WHITELIST.contains(command)) {
                continue;
            }
            String value = String.valueOf(entry.getValue());
            long calls = parseKeyValue(value, "calls");
            long usec = parseKeyValue(value, "usec");
            if (calls <= 0) {
                continue;
            }
            InstanceCommandLatency sample = new InstanceCommandLatency();
            sample.setCollectTime(collectTime);
            sample.setAppId(instanceInfo.getAppId());
            sample.setInstanceId(instanceInfo.getId());
            sample.setCommand(command);
            sample.setCalls(calls);
            sample.setUsec(usec);
            list.add(sample);
        }
        return list;
    }

    private Map<String, Object> section(Map<RedisConstant, Map<String, Object>> infoMap, RedisConstant constant) {
        Map<String, Object> map = infoMap.get(constant);
        return map == null ? Collections.<String, Object>emptyMap() : map;
    }
}
