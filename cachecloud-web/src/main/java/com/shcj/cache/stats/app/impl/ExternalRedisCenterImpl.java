package com.shcj.cache.stats.app.impl;

import com.shcj.cache.constant.AppStatusEnum;
import com.shcj.cache.constant.ImportAppResult;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.ExternalRedisDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.dao.InstanceStatsDao;
import com.shcj.cache.dao.StandardStatsDao;
import com.shcj.cache.entity.StandardStats;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.entity.ExternalRedis;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.InstanceStats;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.task.constant.InstanceInfoEnum;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.enums.BooleanEnum;
import com.shcj.cache.web.vo.ExternalNodeVO;
import com.shcj.cache.web.vo.InstanceIpSearchVO;
import com.shcj.cache.stats.app.ExternalRedisCenter;
import com.shcj.cache.util.AppClusterNoSupport;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.RedisConstUtils;
import com.shcj.cache.util.IdempotentConfirmer;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.util.DateUtil;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Service("externalRedisCenter")
public class ExternalRedisCenterImpl implements ExternalRedisCenter {

    private static final Logger logger = LoggerFactory.getLogger(ExternalRedisCenterImpl.class);
    private final AtomicBoolean scheduledCollecting = new AtomicBoolean(false);

    /** 运行时长/CPU 回溯的采样窗口：采集是分钟级，15 分钟足以拿到相邻两条 */
    private static final long RUNTIME_METRIC_WINDOW_MILLIS = 15L * 60L * 1000L;

    /** 单轮采集的并发度，够把死节点的超时摊开又不至于把 Redis 和自身连接数打上去 */
    private static final int COLLECT_CONCURRENCY = 8;

    /** 一轮超过这个时间就要提醒：再慢下去下一轮会被整轮跳过 */
    private static final long SLOW_ROUND_WARN_MILLIS = 45L * 1000L;

    private final CollectFailureBackoff collectFailureBackoff = new CollectFailureBackoff();

    private final ExecutorService collectExecutor = Executors.newFixedThreadPool(COLLECT_CONCURRENCY,
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "external-redis-collect-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            });

    @Autowired
    private ExternalRedisDao externalRedisDao;
    @Autowired
    private AppDao appDao;
    @Autowired
    private InstanceDao instanceDao;
    @Autowired
    private InstanceStatsDao instanceStatsDao;
    @Autowired
    private RedisCenter redisCenter;
    @Autowired
    private AppService appService;
    @Autowired
    private StandardStatsDao standardStatsDao;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Override
    public ImportAppResult checkName(String name) {
        if (StringUtils.isBlank(name)) {
            return ImportAppResult.fail("应用名称不能为空");
        }
        if (appService.getAppByName(name.trim()) != null) {
            return ImportAppResult.fail("应用名称已存在");
        }
        return ImportAppResult.success();
    }

    @PostConstruct
    public void unbindExternalRedisVersionResourcesOnStartup() {
        try {
            ensureSentinelPasswordColumn();
            List<ExternalRedis> records = externalRedisDao.listAll();
            if (records == null || records.isEmpty()) {
                return;
            }
            int count = 0;
            for (ExternalRedis record : records) {
                if (record == null || record.getAppId() == null || record.getAppId() <= 0) {
                    continue;
                }
                AppDesc appDesc = appService.getByAppId(record.getAppId());
                if (appDesc == null || appDesc.getVersionId() <= 0) {
                    continue;
                }
                appDao.updateVersionId(appDesc.getAppId(), 0);
                count++;
                logger.info("external redis version resource unbound appId={} clusterNo={} name={} oldVersionId={}",
                        appDesc.getAppId(), appDesc.getClusterNo(), appDesc.getName(), appDesc.getVersionId());
            }
            if (count > 0) {
                logger.info("external redis version resource unbind complete count={}", count);
            }
        } catch (Exception e) {
            logger.warn("external redis version resource unbind skipped: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdownCollectExecutor() {
        collectExecutor.shutdownNow();
    }

    @Override
    public void collectStatistics() {
        if (!scheduledCollecting.compareAndSet(false, true)) {
            logger.warn("external redis statistics collection skipped because the previous run is still active");
            return;
        }
        long startMillis = System.currentTimeMillis();
        int appCount = 0;
        try {
            List<ExternalRedis> records = externalRedisDao.listAll();
            if (records == null || records.isEmpty()) return;
            long collectTime = Long.parseLong(DateUtil.formatDate(new Date(), "yyyyMMddHHmm"));

            List<CollectTarget> targets = new ArrayList<CollectTarget>();
            for (ExternalRedis record : records) {
                if (record == null || record.getAppId() == null || record.getAppId() <= 0) continue;
                AppDesc app = appService.getByAppId(record.getAppId());
                if (app == null || !app.isOnline()) continue;
                appCount++;
                List<InstanceInfo> instances = instanceDao.getInstListByAppId(app.getAppId());
                if (instances == null || instances.isEmpty()) continue;
                for (InstanceInfo instance : instances) {
                    // 心跳停止的节点也要继续采集，否则存活探测无法发现恢复（详见 isCollectable）
                    if (!instance.isCollectable() || TypeUtil.isRedisSentinel(instance.getType())) continue;
                    targets.add(new CollectTarget(app.getAppId(), instance.getIp(), instance.getPort()));
                }
            }
            if (targets.isEmpty()) {
                return;
            }

            Set<String> activeKeys = new LinkedHashSet<String>();
            for (CollectTarget target : targets) {
                activeKeys.add(target.key());
            }
            // 下线/删除的节点不该继续占着退避表
            collectFailureBackoff.retainOnly(activeKeys);

            CollectSummary summary = runCollectRound(targets, collectTime);
            long cost = System.currentTimeMillis() - startMillis;
            if (cost > SLOW_ROUND_WARN_MILLIS) {
                // 一轮跑过 60 秒，下一轮就会被 scheduledCollecting 挡掉，等于全平台丢一分钟数据
                logger.warn("external redis statistics collection is running late apps={} nodes={} ok={} failed={} "
                                + "skipped={} cost={}ms collectTime={}", appCount, targets.size(), summary.succeeded,
                        summary.failed, summary.skipped, cost, collectTime);
            } else {
                logger.info("external redis statistics collection submitted apps={} nodes={} ok={} failed={} "
                                + "skipped={} cost={}ms collectTime={}", appCount, targets.size(), summary.succeeded,
                        summary.failed, summary.skipped, cost, collectTime);
            }
        } finally {
            scheduledCollecting.set(false);
        }
    }

    /**
     * 并发跑完一轮采集，等所有任务结束才返回。
     *
     * <p>刻意不设「到点放弃、剩下的任务继续在后台跑」：线程池是固定大小的，
     * 被放弃的任务会一直占着线程，下一轮的任务全部堵在队列里，比现在更糟。
     * 卡死的节点由退避表负责在后续轮次里直接跳过。
     */
    private CollectSummary runCollectRound(List<CollectTarget> targets, final long collectTime) {
        CollectSummary summary = new CollectSummary();
        List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>(targets.size());
        List<CollectTarget> submitted = new ArrayList<CollectTarget>(targets.size());
        long now = System.currentTimeMillis();
        for (final CollectTarget target : targets) {
            if (collectFailureBackoff.shouldSkip(target.key(), now)) {
                summary.skipped++;
                continue;
            }
            submitted.add(target);
            futures.add(collectExecutor.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return collectOneInstance(target, collectTime);
                }
            }));
        }
        for (int i = 0; i < futures.size(); i++) {
            CollectTarget target = submitted.get(i);
            boolean ok;
            try {
                ok = Boolean.TRUE.equals(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("external redis collect interrupted while waiting {}", target.key());
                return summary;
            } catch (Exception e) {
                ok = false;
                logger.warn("external redis collect task failed {}: {}", target.key(), e.getMessage());
            }
            if (ok) {
                collectFailureBackoff.onSuccess(target.key());
                summary.succeeded++;
            } else {
                collectFailureBackoff.onFailure(target.key(), System.currentTimeMillis());
                summary.failed++;
            }
        }
        return summary;
    }

    /**
     * 采集单个实例，返回是否拿到了 info 数据。
     *
     * <p>底层的采集方法把异常都吞在内部了，连不上只会返回空 infoMap，
     * 所以存活与否只能以 info 的返回值为准，不能指望这里的 catch。
     */
    private boolean collectOneInstance(CollectTarget target, long collectTime) {
        try {
            Map<?, ?> infoMap = redisCenter.collectRedisInfo(target.appId, collectTime, target.ip, target.port);
            boolean alive = infoMap != null && !infoMap.isEmpty();
            if (!alive) {
                return false;
            }
            redisCenter.collectRedisSlowLog(target.appId, collectTime, target.ip, target.port);
            redisCenter.collectRedisLatencyInfo(target.appId, collectTime, target.ip, target.port);
            return true;
        } catch (Exception e) {
            logger.warn("scheduled external redis collect failed appId={} {} {}",
                    target.appId, target.key(), e.getMessage());
            return false;
        }
    }

    private static final class CollectTarget {
        private final long appId;
        private final String ip;
        private final int port;

        private CollectTarget(long appId, String ip, int port) {
            this.appId = appId;
            this.ip = ip;
            this.port = port;
        }

        private String key() {
            return ip + ":" + port;
        }
    }

    private static final class CollectSummary {
        private int succeeded;
        private int failed;
        private int skipped;
    }

    private void ensureSentinelPasswordColumn() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'external_redis' "
                        + "AND column_name = 'sentinel_password'",
                Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE external_redis ADD COLUMN sentinel_password VARCHAR(255) NULL AFTER password");
            logger.info("external_redis.sentinel_password column created");
        }
    }

    @Override
    public List<ExternalRedis> listWithInstances() {
        List<ExternalRedis> list = externalRedisDao.listAll();
        if (list == null) {
            return list;
        }
        for (ExternalRedis item : list) {
            if (item.getAppId() != null && item.getAppId() > 0) {
                List<InstanceInfo> instances = instanceDao.getInstListByAppId(item.getAppId());
                item.setInstanceList(instances);
            }
        }
        return list;
    }

    @Override
    public List<ExternalNodeVO> listExternalNodes(String ipQuery) {
        List<ExternalRedis> apps = listWithInstances();
        if (apps == null || apps.isEmpty()) {
            return Collections.emptyList();
        }
        List<ExternalNodeVO> nodes = new ArrayList<ExternalNodeVO>();
        for (ExternalRedis app : apps) {
            long appId = app.getAppId() != null ? app.getAppId() : 0L;
            AppDesc appDesc = appId > 0 ? appService.getByAppId(appId) : null;
            if (appDesc != null && appDesc.isOffline()) {
                continue;
            }
            List<InstanceInfo> instances = app.getInstanceList();
            if (instances == null || instances.isEmpty()) {
                appendNodesFromInstanceInfo(nodes, app, ipQuery);
                continue;
            }
            for (InstanceInfo inst : instances) {
                if (inst.isOffline()) {
                    continue;
                }
                if (!matchesNodeIpFilter(inst.getIp(), inst.getPort(), ipQuery)) {
                    continue;
                }
                nodes.add(buildExternalNodeVO(app, inst, true));
            }
        }
        nodes.sort((ExternalNodeVO a, ExternalNodeVO b) -> {
            int ipCmp = StringUtils.defaultString(a.getIp()).compareTo(StringUtils.defaultString(b.getIp()));
            if (ipCmp != 0) {
                return ipCmp;
            }
            return Integer.compare(a.getPort(), b.getPort());
        });
        return nodes;
    }

    @Override
    public String detectRedisVersionName(ExternalRedis externalRedis) {
        if (externalRedis == null) {
            return "";
        }
        String password = externalRedis.getPassword();
        if (externalRedis.getAppId() != null && externalRedis.getAppId() > 0) {
            AppDesc appDesc = appService.getByAppId(externalRedis.getAppId());
            if (appDesc != null) {
                password = StringUtils.defaultString(appDesc.getCustomPassword());
            }
        }
        String version = detectRedisVersion(externalRedis.getType(), externalRedis.getInstanceInfo(),
                password);
        if (StringUtils.isBlank(version)) {
            return "";
        }
        return "redis-" + version;
    }

    private void appendNodesFromInstanceInfo(List<ExternalNodeVO> nodes, ExternalRedis app, String ipQuery) {
        if (app == null || StringUtils.isBlank(app.getInstanceInfo())) {
            return;
        }
        long appId = app.getAppId() != null ? app.getAppId() : 0L;
        AppDesc appDesc = appId > 0 ? appService.getByAppId(appId) : null;
        if (appDesc != null && appDesc.isOffline()) {
            return;
        }
        String normalized = app.getInstanceInfo().replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n")) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            String[] parts = line.trim().split(":");
            if (parts.length < 2) {
                continue;
            }
            String ip = parts[0].trim();
            int port = NumberUtils.toInt(parts[1].trim());
            if (port <= 0 || !matchesNodeIpFilter(ip, port, ipQuery)) {
                continue;
            }
            ExternalNodeVO vo = new ExternalNodeVO();
            vo.setSynced(false);
            vo.setInstanceId(0);
            vo.setIp(ip);
            vo.setPort(port);
            vo.setAppId(appId);
            if (appDesc != null) {
                vo.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
            }
            // 同上：以 app_desc 为准，避免改名后显示旧名字
            vo.setAppName(appDesc != null ? appDesc.getName() : app.getName());
            vo.setAppTypeDesc(app.getTypeDesc());
            // 纳管信息里有、但尚未同步进 instance_info 的节点，同样实时探活，
            // 不再用「未知」这个既不是正常也不是异常的第三态
            boolean lineNodeAlive = probeNodeAlive(appId, ip, port, isSentinelInstanceLine(
                    parts.length >= 3 ? parts[2].trim() : ""));
            vo.setStatus(lineNodeAlive ? InstanceStatusEnum.GOOD_STATUS.getStatus()
                    : InstanceStatusEnum.ERROR_STATUS.getStatus());
            vo.setStatusDesc(lineNodeAlive ? InstanceStatusEnum.GOOD_STATUS.getInfo()
                    : InstanceStatusEnum.ERROR_STATUS.getInfo());
            String third = parts.length >= 3 ? parts[2].trim() : "";
            if (isSentinelInstanceLine(third)) {
                vo.setNodeTypeDesc("sentinel");
                vo.setRoleDesc("sentinel");
                vo.setCmd(third);
            } else {
                vo.setNodeTypeDesc(resolveDataNodeTypeDesc(app.getType()));
                vo.setRoleDesc("未知");
                vo.setCmd(third);
                int memMb = NumberUtils.toInt(third, 0);
                if (memMb > 0) {
                    vo.setMemMb(memMb);
                    vo.setTotalMemGb(memMb / 1024.0);
                    vo.setMemUsePercent(0);
                }
            }
            nodes.add(vo);
        }
    }

    /** 节点管理页对纳管节点做实时探活；探测本身异常按不可达处理，不覆盖已持久化的其它信息 */
    private boolean probeNodeAlive(long appId, String ip, int port, boolean sentinel) {
        try {
            return sentinel ? redisCenter.isSentinelRun(appId, ip, port) : redisCenter.isRun(appId, ip, port);
        } catch (Exception ignored) {
            return false;
        }
    }

    private ExternalNodeVO buildExternalNodeVO(ExternalRedis app, InstanceInfo inst, boolean synced) {
        ExternalNodeVO vo = new ExternalNodeVO();
        vo.setSynced(synced);
        vo.setInstanceId(inst.getId() != null ? inst.getId() : 0);
        vo.setIp(inst.getIp());
        vo.setPort(inst.getPort());
        boolean reachable = probeNodeAlive(inst.getAppId(), inst.getIp(), inst.getPort(),
                inst.getType() == InstanceInfoEnum.InstanceTypeEnum.REDIS_SENTINEL.getType());
        // 探活失败即为异常，与集群运行状态口径保持一致
        vo.setStatus(reachable ? InstanceStatusEnum.GOOD_STATUS.getStatus()
                : InstanceStatusEnum.ERROR_STATUS.getStatus());
        vo.setStatusDesc(reachable ? InstanceStatusEnum.GOOD_STATUS.getInfo()
                : InstanceStatusEnum.ERROR_STATUS.getInfo());
        vo.setCmd(inst.getCmd());
        vo.setAppId(app.getAppId() != null ? app.getAppId() : inst.getAppId());
        AppDesc appDesc = vo.getAppId() > 0 ? appService.getByAppId(vo.getAppId()) : null;
        if (appDesc != null) {
            vo.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
        }
        // 集群名以 app_desc 为准：external_redis.name 只是纳管当时的快照，
        // 集群改名后不会自动跟着变，直接用它会一直显示旧名字。
        vo.setAppName(appDesc != null ? appDesc.getName() : app.getName());
        vo.setAppTypeDesc(app.getTypeDesc());
        InstanceStats stats = instanceStatsDao.getInstanceStatsByHost(inst.getIp(), inst.getPort());
        if (inst.getType() == InstanceInfoEnum.InstanceTypeEnum.REDIS_SENTINEL.getType()) {
            vo.setNodeTypeDesc("sentinel");
            vo.setRoleDesc("sentinel");
        } else {
            vo.setNodeTypeDesc(resolveDataNodeTypeDesc(app.getType()));
            vo.setRoleDesc(resolveRoleDesc(app, inst, stats));
            fillMemDetail(vo, inst, stats);
            fillRuntimeMetrics(vo, inst, stats);
        }
        return vo;
    }

    private void fillMemDetail(ExternalNodeVO vo, InstanceInfo inst, InstanceStats stats) {
        long memMb = 0;
        double memUsePercent = 0;
        if (stats != null) {
            memUsePercent = stats.getMemUsePercent();
            if (stats.getUsedMemory() > 0) {
                vo.setUsedMemGb(stats.getUsedMemory() / (1024.0 * 1024.0 * 1024.0));
            }
            long maxBytes = stats.getEffectiveMaxMemory();
            if (maxBytes > 0) {
                memMb = maxBytes / (1024L * 1024L);
            }
        }
        if (memMb <= 0 && inst.getMem() > 0) {
            memMb = inst.getMem();
        }
        if (memMb > 0) {
            vo.setMemMb(memMb);
            vo.setTotalMemGb(memMb / 1024.0);
            vo.setMemUsePercent(memUsePercent);
            if (vo.getUsedMemGb() <= 0 && memUsePercent > 0) {
                vo.setUsedMemGb(vo.getTotalMemGb() * memUsePercent / 100.0);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void fillRuntimeMetrics(ExternalNodeVO vo, InstanceInfo inst, InstanceStats stats) {
        if (stats == null) {
            return;
        }
        vo.setKeyCount(Math.max(0L, stats.getCurrItems()));
        Map<String, Object> server = stats.getInfoMap() == null ? null
                : (stats.getInfoMap().get("Server") instanceof Map
                ? (Map<String, Object>) stats.getInfoMap().get("Server")
                : (Map<String, Object>) stats.getInfoMap().get("server"));
        if (server != null) {
            vo.setUptimeSeconds(NumberUtils.toLong(String.valueOf(server.get("uptime_in_seconds")), 0L));
        }
        // 运行时长与 CPU 用的是同一批采样，取一次即可
        List<StandardStats> recentSamples = loadRecentSamples(inst);
        if (vo.getUptimeSeconds() <= 0) {
            vo.setUptimeSeconds(uptimeOf(recentSamples));
        }
        vo.setCpuUsePercent(cpuUsePercentOf(recentSamples));
        vo.setMemFragmentationRatio(stats.getMemFragmentationRatio());
        vo.setConnectedClients(stats.getCurrConnections());
    }

    /**
     * 取该节点最近两条采集快照，按采集时间倒序。
     *
     * <p>原先两个指标各自调 getStandardStatsByCreateTime，那个查询会把全平台
     * 15 分钟窗口内的行连同 info_json 整片捞回来再在内存里挑出本节点的。
     * 节点数一多就是「节点数 × 2」次全窗口查询加上百万字节的 JSON 反序列化，
     * 集群列表因此要跑好几秒。这里按 ip:port 精确取数，只要两条。</p>
     */
    private List<StandardStats> loadRecentSamples(InstanceInfo inst) {
        if (inst == null || StringUtils.isBlank(inst.getIp())) {
            return Collections.emptyList();
        }
        try {
            List<StandardStats> samples = standardStatsDao.getRecentStandardStats(
                    inst.getIp(), inst.getPort(), ConstUtils.REDIS,
                    new Date(System.currentTimeMillis() - RUNTIME_METRIC_WINDOW_MILLIS), 2);
            return samples == null ? Collections.<StandardStats>emptyList() : samples;
        } catch (Exception e) {
            logger.warn("load recent standard stats failed {}:{} {}", inst.getIp(), inst.getPort(), e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private long uptimeOf(List<StandardStats> recentDesc) {
        if (recentDesc.isEmpty()) return 0L;
        StandardStats latest = recentDesc.get(0);
        if (latest.getInfoMap() == null) return 0L;
        Object server = latest.getInfoMap().get("Server");
        if (!(server instanceof Map)) server = latest.getInfoMap().get("server");
        if (!(server instanceof Map)) return 0L;
        Object uptime = ((Map<String, Object>) server).get("uptime_in_seconds");
        return NumberUtils.toLong(uptime == null ? null : String.valueOf(uptime), 0L);
    }

    /** 实例 CPU 字段是累计秒数，按最近两次分钟采集的差值计算百分比。 */
    private double cpuUsePercentOf(List<StandardStats> recentDesc) {
        if (recentDesc.size() < 2) {
            return 0.0D;
        }
        // 入参是倒序的：第 0 条最新
        StandardStats current = recentDesc.get(0);
        StandardStats previous = recentDesc.get(1);
        Map<String, Object> before = previous.getInfoMap();
        Map<String, Object> after = current.getInfoMap();
        double cpuDelta = Math.max(0D, number(after, "used_cpu_sys") - number(before, "used_cpu_sys"))
                + Math.max(0D, number(after, "used_cpu_user") - number(before, "used_cpu_user"))
                + Math.max(0D, number(after, "used_cpu_user_children") - number(before, "used_cpu_user_children"));
        long intervalSeconds = (current.getCollectTime() / 100 - previous.getCollectTime() / 100) * 60L;
        if (intervalSeconds <= 0) {
            return 0.0D;
        }
        return Math.round(Math.max(0D, cpuDelta * 100D / intervalSeconds) * 10D) / 10D;
    }

    @SuppressWarnings("unchecked")
    private double number(Map<String, Object> info, String key) {
        if (info == null) return 0D;
        Object value = info.get(key);
        if (value == null) {
            for (String sectionName : new String[]{"CPU", "cpu"}) {
                Object section = info.get(sectionName);
                if (section instanceof Map) {
                    value = ((Map<String, Object>) section).get(key);
                    if (value != null) break;
                }
            }
        }
        return NumberUtils.toDouble(value == null ? null : String.valueOf(value), 0D);
    }

    /**
     * 节点角色：优先读采集表 instance_statistics，在线节点再实时 INFO replication 探测。
     */
    private String resolveRoleDesc(ExternalRedis app, InstanceInfo inst, InstanceStats stats) {
        if (stats != null) {
            if (stats.getRole() == 1) {
                return "master";
            }
            if (stats.getRole() == 2) {
                return "slave";
            }
        }
        if (inst.isOnline() && app.getAppId() != null && app.getAppId() > 0) {
            BooleanEnum isMaster = redisCenter.isMaster(app.getAppId(), inst.getIp(), inst.getPort());
            if (isMaster == BooleanEnum.TRUE) {
                return "master";
            }
            if (isMaster == BooleanEnum.FALSE) {
                return "slave";
            }
        }
        return "未知";
    }

    private String resolveDataNodeTypeDesc(int appType) {
        if (appType == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            return "redis-cluster";
        }
        return "redis-server";
    }

    private boolean matchesNodeIpFilter(String ip, int port, String ipQuery) {
        if (StringUtils.isBlank(ipQuery)) {
            return true;
        }
        String query = ipQuery.trim();
        int colon = query.lastIndexOf(':');
        if (colon > 0 && colon < query.length() - 1) {
            String portPart = query.substring(colon + 1).trim();
            if (NumberUtils.isDigits(portPart)) {
                String ipPart = query.substring(0, colon).trim();
                return StringUtils.defaultString(ip).contains(ipPart) && port == Integer.parseInt(portPart);
            }
        }
        return StringUtils.defaultString(ip).contains(query);
    }

    @Override
    public ImportAppResult check(int type, String appInstanceInfo, String password) {
        return check(type, appInstanceInfo, password, "");
    }

    @Override
    public ImportAppResult check(int type, String appInstanceInfo, String password, String sentinelPassword) {
        ImportAppResult typeCheck = validateAppType(type);
        if (typeCheck.getStatus() != 1) {
            return typeCheck;
        }
        if (StringUtils.isBlank(appInstanceInfo)) {
            return ImportAppResult.fail("实例详情为空");
        }
        String[] appInstanceDetails = appInstanceInfo.split("\n");
        String masterNameInput = "";
        if (type == ConstUtils.CACHE_REDIS_SENTINEL) {
            ImportAppResult sentinelFormatResult = checkSentinelImportFormat(appInstanceDetails, password,
                    sentinelPassword);
            if (sentinelFormatResult.getStatus() != 1) {
                return sentinelFormatResult;
            }
        }
        for (String appInstance : appInstanceDetails) {
            if (StringUtils.isBlank(appInstance)) {
                return ImportAppResult.fail("应用实例信息有空行");
            }
            String[] instanceItems = appInstance.trim().split(":");
            if (instanceItems.length < 2) {
                return ImportAppResult.fail("应用实例信息" + appInstance + "格式错误，至少为 ip:port");
            }
            String ip = instanceItems[0].trim();
            String portStr = instanceItems[1].trim();
            boolean portIsDigit = NumberUtils.isDigits(portStr);
            if ((!portIsDigit) && (type != ConstUtils.CACHE_REDIS_SENTINEL)) {
                return ImportAppResult.fail(appInstance + "中的 port 不是整数");
            } else if ((!portIsDigit) && (type == ConstUtils.CACHE_REDIS_SENTINEL)) {
                masterNameInput = instanceItems[0].trim();
                continue;
            }
            if (instanceItems.length == 2 && looksLikeMemoryMb(NumberUtils.toInt(portStr))) {
                return ImportAppResult.fail("实例格式错误：" + appInstance
                        + " 缺少 Redis 端口。单机/集群请使用 ip:port，例如 " + ip + ":6379");
            }
            if (instanceItems.length == 2
                    && (type == ConstUtils.CACHE_REDIS_STANDALONE || type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER)) {
                portStr = instanceItems[1].trim();
            }
            int port = NumberUtils.toInt(portStr);
            ImportAppResult existResult = checkNodeNotExists(ip, port);
            if (existResult.getStatus() != 1) {
                return existResult;
            }
            boolean sentinelNode = type == ConstUtils.CACHE_REDIS_SENTINEL
                    && instanceItems.length >= 3 && isSentinelInstanceLine(instanceItems[2].trim());
            String nodePassword = sentinelNode ? sentinelPassword : password;
            ImportAppResult pingResult = pingNode(ip, port, nodePassword, type);
            if (pingResult.getStatus() != 1) {
                return ImportAppResult.fail(appInstance + "：" + pingResult.getMessage());
            }
            if (StringUtils.isNotEmpty(masterNameInput) && (type == ConstUtils.CACHE_REDIS_SENTINEL)) {
                String masterName = getSentinelMasterName(ip, port, sentinelPassword);
                if (StringUtils.isEmpty(masterName) || !masterNameInput.equals(masterName)) {
                    return ImportAppResult.fail(ip + ":" + port + ", masterName:" + masterName + "与所填"
                            + masterNameInput + "不一致");
                }
            }
        }
        try {
            RedisVersionResolution version = resolveRedisVersion(0, type, appInstanceInfo, password);
            return new ImportAppResult(1, "所有检查都成功，可以添加啦! Redis版本：" + RedisConstUtils.displayVersion(version.getVersionName()));
        } catch (IllegalStateException e) {
            return ImportAppResult.fail(e.getMessage());
        }
    }

    private ImportAppResult checkSentinelImportFormat(String[] appInstanceDetails, String redisPassword,
                                                       String sentinelPassword) {
        int dataLineCount = 0;
        int sentinelLineCount = 0;
        for (String rawLine : appInstanceDetails) {
            if (StringUtils.isBlank(rawLine)) {
                continue;
            }
            String line = rawLine.trim();
            String[] parts = line.split(":");
            if (parts.length < 2) {
                return ImportAppResult.fail("sentinel import line " + line
                        + " 格式错误. 数据节点格式: ip:port; 哨兵节点格式: ip:port:masterName");
            }
            String ip = parts[0].trim();
            String portText = parts[1].trim();
            if (!NumberUtils.isDigits(portText)) {
                return ImportAppResult.fail(line + " port is not numeric");
            }
            int port = NumberUtils.toInt(portText);
            if (parts.length == 2 || StringUtils.isBlank(parts[2])) {
                dataLineCount++;
                if (isSentinelRuntimeNode(ip, port, redisPassword)) {
                    return ImportAppResult.fail(ip + ":" + port
                            + " is a Sentinel node. Use ip:port:masterName for Sentinel nodes");
                }
                continue;
            }
            String marker = parts[2].trim();
            if (isSentinelInstanceLine(marker)) {
                sentinelLineCount++;
                String masterName = getSentinelMasterName(ip, port, sentinelPassword);
                if (StringUtils.isBlank(masterName) || !marker.equals(masterName)) {
                    return ImportAppResult.fail(ip + ":" + port + " sentinel masterName " + masterName
                            + " does not match " + marker);
                }
            } else if (isDataInstanceLine(marker)) {
                dataLineCount++;
                if (isSentinelRuntimeNode(ip, port, redisPassword)) {
                    return ImportAppResult.fail(ip + ":" + port
                            + " is a Sentinel node. Use ip:port:masterName for Sentinel nodes");
                }
            } else {
                return ImportAppResult.fail("invalid sentinel import line " + line
                        + ". Use ip:port for Redis data nodes or ip:port:masterName for Sentinel nodes");
            }
        }
        if (dataLineCount == 0) {
            return ImportAppResult.fail("sentinel cluster import requires at least one Redis data node");
        }
        if (sentinelLineCount == 0) {
            return ImportAppResult.fail("sentinel cluster import requires Sentinel nodes in ip:port:masterName format");
        }
        if (sentinelLineCount < 3) {
            return ImportAppResult.fail("sentinel cluster import requires at least 3 Sentinel nodes");
        }
        return ImportAppResult.success();
    }

    private boolean isSentinelRuntimeNode(String ip, int port, String password) {
        try {
            String info = jedisInfoAll(ip, port, password);
            return info != null && info.contains("# Sentinel");
        } catch (Exception e) {
            logger.warn("{}:{} sentinel runtime check failed: {}", ip, port, e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportAppResult register(AppUser currentUser, String name, String intro, int type,
                                    int isTest, String officer, String password, String appInstanceInfo,
                                    int versionId) {
        return register(currentUser, name, intro, type, isTest, officer, password, "",
                appInstanceInfo, versionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportAppResult register(AppUser currentUser, String name, String intro, int type,
                                    int isTest, String officer, String password, String sentinelPassword,
                                    String appInstanceInfo, int versionId) {
        ImportAppResult nameCheck = checkName(name);
        if (nameCheck.getStatus() != 1) {
            return nameCheck;
        }
        ImportAppResult typeCheck = validateAppType(type);
        if (typeCheck.getStatus() != 1) {
            return typeCheck;
        }
        ImportAppResult checkResult = check(type, appInstanceInfo, password, sentinelPassword);
        if (checkResult.getStatus() != 1) {
            logger.warn("external redis register check failed: {}", checkResult.getMessage());
            return checkResult;
        }
        try {
            Date now = new Date();
            RedisVersionResolution resolvedVersion = resolveRedisVersion(versionId, type, appInstanceInfo, password);
            AppDesc appDesc = buildAppDesc(currentUser, name, intro, type, isTest, officer, password,
                    now, resolvedVersion.getResourceId());
            appDesc.setVersionName(resolvedVersion.getVersionName());
            appService.save(appDesc);
            long appId = appDesc.getAppId();
            // 先写入 external_redis，保证同一次纳管内「补写」与诊断能读到 instance_info
            ExternalRedis record = buildExternalRedisRecord(appId, name, intro, type, password, sentinelPassword,
                    appInstanceInfo, currentUser.getId(), now);
            externalRedisDao.save(record);
            appService.updateAppKey(appId);
            appService.saveAppToUser(appId, currentUser.getId());
            syncInstancesOrThrow(appId, type, appInstanceInfo);
            logger.info("external redis register ok appId={} name={} redisVersion={} resourceId={} instances={}",
                    appId, name, resolvedVersion.getVersionName(), resolvedVersion.getResourceId(),
                    sizeOf(instanceDao.getInstListByAppId(appId)));
            registerScheduleAndCollect(appId, type);
            return ImportAppResult.success();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            // 异常在此被吞掉，不会传播到 @Transactional 代理，事务默认仍会提交，
            // 导致纳管失败后残留一条没有实例、没有 appKey 的僵尸 app_desc 记录。
            markRollbackOnly();
            return ImportAppResult.fail("纳管失败: " + e.getMessage());
        }
    }

    /**
     * 在捕获异常后显式标记回滚，保证纳管要么整体成功、要么不留下半成品记录。
     */
    private void markRollbackOnly() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (RuntimeException ex) {
            logger.warn("mark rollback-only failed", ex);
        }
    }

    private ExternalRedis buildExternalRedisRecord(long appId, String name, String intro, int type, String password,
                                                   String sentinelPassword, String appInstanceInfo, long userId,
                                                   Date now) {
        ExternalRedis record = new ExternalRedis();
        record.setAppId(appId);
        record.setName(name);
        record.setIntro(intro);
        record.setType(type);
        record.setPassword(StringUtils.defaultString(password));
        record.setSentinelPassword(StringUtils.defaultString(sentinelPassword));
        record.setInstanceInfo(appInstanceInfo);
        record.setUserId(userId);
        record.setStatus(1);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        return record;
    }

    private AppDesc buildAppDesc(AppUser currentUser, String name, String intro, int type,
                                 int isTest, String officer, String password, Date now, int versionId) {
        AppDesc appDesc = new AppDesc();
        appDesc.setName(name);
        appDesc.setIntro(intro);
        if (StringUtils.isNotBlank(officer)) {
            String firstOfficer = officer.split(",")[0].trim();
            appDesc.setOfficer(StringUtils.isNumeric(firstOfficer) ? firstOfficer : String.valueOf(currentUser.getId()));
        } else {
            appDesc.setOfficer(String.valueOf(currentUser.getId()));
        }
        appDesc.setType(type);
        appDesc.setIsTest(isTest);
        appDesc.setUserId(currentUser.getId());
        appDesc.setStatus(AppStatusEnum.STATUS_PUBLISHED.getStatus());
        appDesc.setCreateTime(now);
        appDesc.setPassedTime(now);
        appDesc.setVerId(1);
        appDesc.setVersionId(versionId);
        // 纳管即开启告警：该字段现在是集群级告警总闸，默认 0 会让新纳管的集群完全静默
        appDesc.setIsAccessMonitor(1);
        appDesc.setImportantLevel(2);
        if (StringUtils.isNotBlank(password)) {
            appDesc.setCustomPassword(password);
        }
        return appDesc;
    }

    /**
     * 纳管时同步写入 instance_info（与原有「应用导入」一致），失败则整单回滚。
     * 历史数据若只有 external_redis 无实例，由列表页/collectNow 自动补写。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportAppResult addInstances(long appId, String appInstanceInfo) {
        if (appId <= 0) {
            return ImportAppResult.fail("appId 无效");
        }
        if (StringUtils.isBlank(appInstanceInfo)) {
            return ImportAppResult.fail("请填写要新增的节点");
        }
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            return ImportAppResult.fail("集群不存在");
        }
        ExternalRedis record = externalRedisDao.getByAppId(appId);
        if (record == null) {
            // 非纳管集群的节点由部署流程创建，不能只往登记表里追加一行了事
            return ImportAppResult.fail("该集群不是外部纳管集群，无法通过此入口新增节点");
        }
        try {
            List<String> newLines = normalizeInstanceLines(appInstanceInfo);
            if (newLines.isEmpty()) {
                return ImportAppResult.fail("节点格式不正确，请按 ip:port 每行一个填写");
            }
            Set<String> existing = new LinkedHashSet<String>(normalizeInstanceLines(record.getInstanceInfo()));
            List<String> toAdd = new ArrayList<String>();
            for (String line : newLines) {
                // 登记清单里还挂着，不等于节点真的还在：删除路径可能没清干净登记表。
                // 只有实例确实处于在册状态才算重复，否则按「修复」处理让它能加回来。
                if (existing.contains(line) && isLiveInstanceOfApp(appId, line)) {
                    return ImportAppResult.fail("节点 " + line + " 已在本集群中，无需重复添加");
                }
                toAdd.add(line);
            }
            // 逐个校验：连通性 + 是否已被其它集群占用，全部通过才落库
            for (String line : toAdd) {
                String[] parts = line.split(":");
                String ip = parts[0].trim();
                int port = NumberUtils.toInt(parts[1].trim());
                ImportAppResult occupied = checkNodeNotExists(ip, port, appId);
                if (occupied.getStatus() != 1) {
                    return occupied;
                }
                boolean sentinelLine = parts.length >= 3 && isSentinelInstanceLine(parts[2].trim());
                if (!probeNodeAlive(appId, ip, port, sentinelLine)) {
                    return ImportAppResult.fail("节点 " + line + " 连接失败，请确认地址正确且 Redis 已启动");
                }
            }

            existing.addAll(toAdd);
            String merged = StringUtils.join(existing, "\n");
            externalRedisDao.updateInstanceInfo(appId, merged);
            int saved = saveInstances(appId, appDesc.getType(), StringUtils.join(toAdd, "\n"));
            logger.info("addInstances appId={} added={} lines={}", appId, saved, toAdd);

            // 按真实拓扑回填主从关系（parent_id）。复用定时任务同款逻辑：
            // 它从节点自身读 CLUSTER NODES / INFO replication，不依赖用户填写。
            try {
                int synced = appService.syncAppTopology(appId);
                logger.info("addInstances topology synced appId={} instancesUpdated={}", appId, synced);
            } catch (Exception e) {
                // 拓扑回填失败不该让整个新增回滚：节点已登记，定时任务下一轮会补上
                logger.warn("addInstances topology sync failed appId={}: {}", appId, e.getMessage());
            }

            ImportAppResult result = ImportAppResult.success();
            result.setMessage("已新增 " + saved + " 个节点，约 1 分钟后统计数据可见");
            return result;
        } catch (Exception e) {
            logger.error("addInstances failed appId=" + appId, e);
            markRollbackOnly();
            return ImportAppResult.fail("新增节点失败: " + e.getMessage());
        }
    }

    @Override
    public boolean removeInstanceLine(long appId, String ip, int port) {
        if (appId <= 0 || StringUtils.isBlank(ip) || port <= 0) {
            return false;
        }
        ExternalRedis record = externalRedisDao.getByAppId(appId);
        if (record == null || StringUtils.isBlank(record.getInstanceInfo())) {
            return false;
        }
        String prefix = ip.trim() + ":" + port;
        List<String> kept = new ArrayList<String>();
        boolean removed = false;
        for (String line : normalizeInstanceLines(record.getInstanceInfo())) {
            // 同一 ip:port 可能带第三段（哨兵 masterName / 数据节点内存），按前缀匹配
            if (line.equals(prefix) || line.startsWith(prefix + ":")) {
                removed = true;
                continue;
            }
            kept.add(line);
        }
        if (!removed) {
            return false;
        }
        externalRedisDao.updateInstanceInfo(appId, StringUtils.join(kept, "\n"));
        logger.info("removeInstanceLine appId={} {}:{} removed from external record", appId, ip, port);
        return true;
    }

    /** 该行对应的实例是否仍在本集群且未被移出 */
    private boolean isLiveInstanceOfApp(long appId, String line) {
        String[] parts = line.split(":");
        if (parts.length < 2) {
            return false;
        }
        InstanceInfo exists = instanceDao.getAllInstByIpAndPort(parts[0].trim(),
                NumberUtils.toInt(parts[1].trim()));
        return exists != null && exists.getAppId() == appId && !isRemovedStatus(exists.getStatus());
    }

    /** 把多行节点文本规整成去空行、去首尾空格的列表，供比对与拼接使用 */
    private List<String> normalizeInstanceLines(String text) {
        List<String> lines = new ArrayList<String>();
        if (StringUtils.isBlank(text)) {
            return lines;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String raw : normalized.split("\n")) {
            String line = StringUtils.trimToEmpty(raw);
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(":");
            if (parts.length < 2 || NumberUtils.toInt(parts[1].trim()) <= 0) {
                continue;
            }
            lines.add(line);
        }
        return lines;
    }

    private void syncInstancesOrThrow(long appId, int type, String appInstanceInfo) {
        int saved = saveInstances(appId, type, appInstanceInfo);
        if (hasInstances(appId)) {
            logger.info("syncInstances ok appId={} saved={} dbCount={}", appId, saved,
                    sizeOf(instanceDao.getInstListByAppId(appId)));
            return;
        }
        if (saved <= 0) {
            throw new IllegalStateException("未写入任何节点，请填写节点详情后重试");
        }
        logger.warn("saveInstances saved={} but instance_info still empty appId={}, retry once", saved, appId);
        saveInstances(appId, type, appInstanceInfo);
        if (hasInstances(appId)) {
            return;
        }
        int repaired = repairInstancesFromExternalRecord(appId);
        if (!hasInstances(appId)) {
            throw new IllegalStateException("节点写入失败，请确认地址格式正确，且未被其他集群占用");
        }
        logger.info("syncInstances repaired appId={} added={}", appId, repaired);
    }

    private boolean hasInstances(long appId) {
        List<InstanceInfo> list = instanceDao.getInstListByAppId(appId);
        return list != null && !list.isEmpty();
    }

    /**
     * 从 external_redis.instance_info 补写 instance_info（修复历史纳管缺实例）。
     */
    private int repairInstancesFromExternalRecord(long appId) {
        ExternalRedis record = externalRedisDao.getByAppId(appId);
        if (record == null || StringUtils.isBlank(record.getInstanceInfo())) {
            return 0;
        }
        int before = sizeOf(instanceDao.getInstListByAppId(appId));
        int effectiveType = resolveEffectiveAppType(record.getType(), record.getInstanceInfo());
        if (effectiveType != record.getType()) {
            AppDesc patch = appService.getByAppId(appId);
            if (patch != null) {
                patch.setType(effectiveType);
                appService.update(patch);
                logger.info("repairInstances corrected app type appId={} {} -> {}", appId, record.getType(),
                        effectiveType);
            }
        }
        int saved = saveInstances(appId, effectiveType, record.getInstanceInfo());
        if (saved <= 0 && StringUtils.isBlank(record.getInstanceInfo())) {
            throw new IllegalStateException("缺少节点详情，无法补写，请重新纳管并填写节点信息");
        }
        int after = sizeOf(instanceDao.getInstListByAppId(appId));
        int added = after - before;
        if (added > 0) {
            logger.info("repairInstances appId={} added={}", appId, added);
        }
        return added;
    }

    private int sizeOf(List<InstanceInfo> list) {
        return list == null ? 0 : list.size();
    }

    private int saveInstances(long appId, int type, String appInstanceInfo) {
        if (StringUtils.isBlank(appInstanceInfo)) {
            return 0;
        }
        String normalized = appInstanceInfo.replace("\r\n", "\n").replace('\r', '\n');
        String[] appInstanceDetails = normalized.split("\n");
        int saved = 0;
        for (String appInstance : appInstanceDetails) {
            if (StringUtils.isBlank(appInstance)) {
                continue;
            }
            String line = appInstance.trim();
            String[] instanceItems = line.split(":");
            if (instanceItems.length < 2) {
                logger.warn("skip invalid instance line appId={} line={}", appId, line);
                continue;
            }
            String host = instanceItems[0].trim();
            int port = NumberUtils.toInt(instanceItems[1].trim());
            if (port <= 0) {
                logger.warn("skip invalid port appId={} line={}", appId, line);
                continue;
            }
            // 仅 ip:port：按数据节点写入，mem 不填默认 0（不写假内存）
            if (instanceItems.length < 3 || StringUtils.isBlank(instanceItems[2])) {
                int instanceType = resolveDataInstanceType(type);
                saveInstance(appId, host, port, 0, instanceType, "");
                saved++;
                continue;
            }
            String memoryOrMasterName = instanceItems[2].trim();
            if (isSentinelInstanceLine(memoryOrMasterName)) {
                saveInstance(appId, host, port, 0, ConstUtils.CACHE_REDIS_SENTINEL, memoryOrMasterName);
                saved++;
            } else if (isDataInstanceLine(memoryOrMasterName)) {
                int memMb = NumberUtils.toInt(memoryOrMasterName);
                int instanceType = resolveDataInstanceType(type);
                saveInstance(appId, host, port, memMb, instanceType, "");
                saved++;
            } else {
                logger.warn("skip instance unknown app type appId={} type={} line={}", appId, type, line);
            }
        }
        logger.info("saveInstances appId={} saved={}", appId, saved);
        return saved;
    }

    private InstanceInfo saveInstance(long appId, String host, int port, int maxMemory, int type, String cmd) {
        InstanceInfo exists = instanceDao.getAllInstByIpAndPort(host, port);
        if (exists != null) {
            if (exists.getAppId() == appId) {
                // 同集群下被删过的节点会留下一行「已下线/永久下线」记录。
                // 原先这里直接 return，状态永远停在已下线：存活探测会跳过下线节点，
                // 于是节点看似加回来了却永远不恢复，也再采集不到数据。
                if (isRemovedStatus(exists.getStatus())) {
                    logger.info("reactivate removed node {}:{} of appId={} (status {} -> good)",
                            host, port, appId, exists.getStatus());
                    instanceDao.reuseInstance(exists.getId(), appId, ExternalRedis.EXTERNAL_HOST_ID, host, port,
                            InstanceStatusEnum.GOOD_STATUS.getStatus(), maxMemory, 0, cmd, type);
                    exists.setStatus(InstanceStatusEnum.GOOD_STATUS.getStatus());
                    exists.setMem(maxMemory);
                    exists.setCmd(cmd);
                    exists.setType(type);
                    return exists;
                }
                logger.debug("saveInstance skip existing appId={} {}:{}", appId, host, port);
                return exists;
            }
            if (canReuseStoppedClusterNode(exists)) {
                logger.info("reuse stopped cluster node {}:{} from appId={} to appId={}",
                        host, port, exists.getAppId(), appId);
                instanceDao.reuseInstance(exists.getId(), appId, ExternalRedis.EXTERNAL_HOST_ID, host, port,
                        InstanceStatusEnum.GOOD_STATUS.getStatus(), maxMemory, 0, cmd, type);
                exists.setAppId(appId);
                exists.setHostId(ExternalRedis.EXTERNAL_HOST_ID);
                exists.setStatus(InstanceStatusEnum.GOOD_STATUS.getStatus());
                exists.setMem(maxMemory);
                exists.setConn(0);
                exists.setCmd(cmd);
                exists.setType(type);
                exists.setMasterInstanceId(0);
                return exists;
            }
            throw new IllegalStateException(occupiedNodeMessage(host, port, exists));
        }
        InstanceInfo instanceInfo = new InstanceInfo();
        instanceInfo.setAppId(appId);
        instanceInfo.setHostId(ExternalRedis.EXTERNAL_HOST_ID);
        instanceInfo.setConn(0);
        instanceInfo.setMem(maxMemory);
        instanceInfo.setStatus(InstanceStatusEnum.GOOD_STATUS.getStatus());
        instanceInfo.setPort(port);
        instanceInfo.setType(type);
        instanceInfo.setCmd(cmd);
        instanceInfo.setIp(host);
        try {
            instanceDao.saveInstance(instanceInfo);
            if (instanceInfo.getId() <= 0) {
                throw new IllegalStateException("节点 " + host + ":" + port + " 写入失败，请稍后重试");
            }
            logger.info("saveInstance ok appId={} instId={} {}:{}", appId, instanceInfo.getId(), host, port);
        } catch (Exception e) {
            InstanceInfo conflict = instanceDao.getAllInstByIpAndPort(host, port);
            if (conflict != null) {
                if (conflict.getAppId() == appId) {
                    return conflict;
                }
                if (canReuseStoppedClusterNode(conflict)) {
                    logger.info("reuse stopped cluster node after save conflict {}:{} from appId={} to appId={}",
                            host, port, conflict.getAppId(), appId);
                    instanceDao.reuseInstance(conflict.getId(), appId, ExternalRedis.EXTERNAL_HOST_ID, host, port,
                            InstanceStatusEnum.GOOD_STATUS.getStatus(), maxMemory, 0, cmd, type);
                    return conflict;
                }
                throw new IllegalStateException(occupiedNodeMessage(host, port, conflict));
            }
            throw new IllegalStateException("节点 " + host + ":" + port + " 写入失败，请稍后重试", e);
        }
        return instanceInfo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportAppResult removeInstance(long appId, int instanceId) {
        if (appId <= 0 || instanceId <= 0) {
            return ImportAppResult.fail("参数无效");
        }
        InstanceInfo target = instanceDao.getInstanceInfoById(instanceId);
        if (target == null) {
            return ImportAppResult.fail("节点不存在");
        }
        if (target.getAppId() != appId) {
            return ImportAppResult.fail("节点不属于当前集群");
        }
        if (isRemovedStatus(target.getStatus())) {
            return ImportAppResult.fail("节点已从平台移除，无需重复操作");
        }
        // 至少保留一个在册节点，避免把集群摘空后连补写的依据都没有了
        int remaining = 0;
        List<InstanceInfo> all = instanceDao.getInstListByAppId(appId);
        if (all != null) {
            for (InstanceInfo info : all) {
                if (info.getId() != null && info.getId() != instanceId && !isRemovedStatus(info.getStatus())) {
                    remaining++;
                }
            }
        }
        if (remaining <= 0) {
            return ImportAppResult.fail("这是集群最后一个节点，移除后集群将没有任何节点");
        }
        try {
            // 仅改平台侧状态：不下发 CLUSTER FORGET，不关进程，真实集群拓扑不受影响
            instanceDao.updateStatus(appId, target.getIp(), target.getPort(),
                    InstanceStatusEnum.FORGET_STATUS.getStatus());
            removeInstanceLine(appId, target.getIp(), target.getPort());
            logger.warn("remove instance from platform only appId={} {}:{} (redis untouched)",
                    appId, target.getIp(), target.getPort());
            ImportAppResult result = ImportAppResult.success();
            result.setMessage("节点 " + target.getHostPort() + " 已从平台移除（真实集群未做任何变更）");
            return result;
        } catch (Exception e) {
            logger.error("removeInstance failed appId=" + appId + " instanceId=" + instanceId, e);
            markRollbackOnly();
            return ImportAppResult.fail("移除节点失败: " + e.getMessage());
        }
    }

    /** 已被移出集群的状态：这类记录允许重新纳入本集群 */
    private boolean isRemovedStatus(int status) {
        return status == InstanceStatusEnum.OFFLINE_STATUS.getStatus()
                || status == InstanceStatusEnum.FORGET_STATUS.getStatus();
    }

    /**
     * 面向「往指定集群加节点」的占用校验。
     *
     * <p>与不带 appId 的版本的区别：本集群自己删掉的节点允许再加回来，
     * 否则节点一旦删除就永久无法恢复，只能改库。</p>
     */
    private ImportAppResult checkNodeNotExists(String ip, int port, long appId) {
        InstanceInfo exists = instanceDao.getAllInstByIpAndPort(ip, port);
        if (exists != null && exists.getAppId() == appId && isRemovedStatus(exists.getStatus())) {
            return ImportAppResult.success();
        }
        return checkNodeNotExists(ip, port);
    }

    /** 检查节点是否已被平台纳管（用户可见文案，不暴露表名）。 */
    private ImportAppResult checkNodeNotExists(String ip, int port) {
        InstanceInfo exists = instanceDao.getAllInstByIpAndPort(ip, port);
        if (exists != null) {
            if (canReuseStoppedClusterNode(exists)) {
                logger.info("node {}:{} belongs to stopped cluster appId={}, allow reusing",
                        ip, port, exists.getAppId());
                return ImportAppResult.success();
            }
            return ImportAppResult.fail(occupiedNodeMessage(ip, port, exists));
        }
        InstanceStats instanceStats = instanceStatsDao.getInstanceStatsByHost(ip, port);
        if (instanceStats != null) {
            return ImportAppResult.fail("节点 " + ip + ":" + port + " 已在平台中存在监控记录，无法重复添加");
        }
        return ImportAppResult.success();
    }

    private boolean canReuseStoppedClusterNode(InstanceInfo exists) {
        if (exists == null) {
            return false;
        }
        AppDesc appDesc = appService.getByAppId(exists.getAppId());
        return appDesc == null || appDesc.getStatus() != AppStatusEnum.STATUS_PUBLISHED.getStatus();
    }

    private String occupiedNodeMessage(String ip, int port, InstanceInfo exists) {
        String addr = ip + ":" + port;
        int status = exists.getStatus();
        if (status == InstanceStatusEnum.OFFLINE_STATUS.getStatus()
                || status == InstanceStatusEnum.FORGET_STATUS.getStatus()) {
            return "节点 " + addr + " 已下线，无法再次添加";
        }
        AppDesc occupied = appService.getByAppId(exists.getAppId());
        if (occupied != null && StringUtils.isNotBlank(occupied.getName())) {
            return "节点 " + addr + " 已在平台中纳管（所属集群：" + occupied.getName() + "），无法重复添加";
        }
        return "节点 " + addr + " 已在平台中纳管，无法重复添加";
    }

    /**
     * 通过 Jedis 直连探活（不 SSH、不依赖本机 redis-cli）。
     */
    private ImportAppResult pingNode(String ip, int port, String password, int appType) {
        try {
            String pong = jedisPing(ip, port, password);
            if (pong != null && pong.trim().toUpperCase().contains("PONG")) {
                return ImportAppResult.success();
            }
            return ImportAppResult.fail("PING 返回异常: " + pong);
        } catch (JedisDataException e) {
            return mapJedisAuthError(ip, port, e);
        } catch (JedisConnectionException e) {
            return ImportAppResult.fail("无法连接 " + ip + ":" + port + "（" + e.getMessage()
                    + "）。请确认 CacheCloud 服务器能访问该地址");
        } catch (Exception e) {
            return ImportAppResult.fail("连接失败: " + e.getMessage());
        }
    }

    private String jedisPing(String ip, int port, String password) {
        Jedis jedis = null;
        try {
            jedis = openJedis(ip, port, password);
            return jedis.ping();
        } finally {
            closeJedis(jedis);
        }
    }

    private String jedisInfoAll(String ip, int port, String password) {
        Jedis jedis = null;
        try {
            jedis = openJedis(ip, port, password);
            return jedis.info("all");
        } finally {
            closeJedis(jedis);
        }
    }

    private Jedis openJedis(String ip, int port, String password) {
        if (StringUtils.isNotBlank(password)) {
            return redisCenter.getJedis(ip, port, password);
        }
        return redisCenter.getJedis(ip, port);
    }

    private void closeJedis(Jedis jedis) {
        if (jedis != null) {
            jedis.close();
        }
    }

    private ImportAppResult mapJedisAuthError(String ip, int port, Exception e) {
        String msg = e.getMessage();
        if (msg != null && msg.toUpperCase().contains("WRONGPASS")) {
            return ImportAppResult.fail("Redis 密码错误（WRONGPASS），请填写该实例 requirepass/ACL 的正确密码");
        }
        if (msg != null && msg.toUpperCase().contains("NOAUTH")) {
            return ImportAppResult.fail("Redis 需要密码，请填写正确密码");
        }
        return ImportAppResult.fail("无法连接 " + ip + ":" + port + "（" + msg + "）");
    }

    /**
     * 第二段为常见内存大小时，多半是漏写了端口（如 139.x.x.x:2048）。
     */
    @Override
    public ImportAppResult repairInstances(long appId) {
        if (appId <= 0) {
            return ImportAppResult.fail("appId 无效");
        }
        int added;
        try {
            added = repairInstancesFromExternalRecord(appId);
        } catch (IllegalStateException e) {
            return ImportAppResult.fail(e.getMessage());
        }
        if (added <= 0) {
            List<InstanceInfo> list = instanceDao.getInstListByAppId(appId);
            if (list != null && !list.isEmpty()) {
                ImportAppResult result = ImportAppResult.success();
                result.setMessage("实例已存在，共 " + list.size() + " 个");
                return result;
            }
            return ImportAppResult.fail("未能补写节点：可能已被其他集群占用，或缺少节点详情，请重新纳管");
        }
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc != null) {
            registerScheduleAndCollect(appId, appDesc.getType());
        }
        ImportAppResult result = ImportAppResult.success();
        result.setMessage("已补写 " + added + " 个实例并触发采集");
        return result;
    }

    @Override
    public ImportAppResult collectNow(long appId) {
        logger.info("external redis collectNow called appId={}", appId);
        if (appId <= 0) {
            return ImportAppResult.fail("appId 无效");
        }
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            return ImportAppResult.fail("应用不存在");
        }
        if (!hasInstances(appId)) {
            repairInstancesFromExternalRecord(appId);
        }
        registerScheduleAndCollect(appId, appDesc.getType());
        ImportAppResult result = ImportAppResult.success();
        result.setMessage("已触发采集，请 1 分钟后刷新应用统计页");
        return result;
    }

    @Override
    public Map<String, Object> diagnose(long appId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appId", appId);
        result.put("javaUserHome", System.getProperty("user.home"));
        result.put("redisClient", "jedis");
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            result.put("ok", false);
            result.put("error", "应用不存在 appId=" + appId);
            return result;
        }
        result.put("appName", appDesc.getName());
        result.put("hasPassword", StringUtils.isNotBlank(appDesc.getAppPassword()));
        List<InstanceInfo> instances = instanceDao.getInstListByAppId(appId);
        if (instances == null || instances.isEmpty()) {
            int repaired = repairInstancesFromExternalRecord(appId);
            result.put("repairedInstances", repaired);
            instances = instanceDao.getInstListByAppId(appId);
        }
        if (instances == null || instances.isEmpty()) {
            result.put("ok", false);
            result.put("error", "该集群尚无节点记录，请重新纳管并填写节点详情");
            return result;
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        boolean allOk = true;
        long collectTime = Long.parseLong(DateUtil.formatDate(new Date(), "yyyyMMddHHmm"));
        for (InstanceInfo inst : instances) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("ip", inst.getIp());
            node.put("port", inst.getPort());
            node.put("type", inst.getType());
            node.put("hostId", inst.getHostId());
            node.put("status", inst.getStatus());
            node.put("externalManaged", inst.getHostId() == ExternalRedis.EXTERNAL_HOST_ID);
            if (inst.getStatus() != InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                node.put("statusWarn", "status 应为 1(正常)，否则 getInstByIpAndPort 查不到");
            }
            if (inst.getHostId() != ExternalRedis.EXTERNAL_HOST_ID) {
                node.put("hostIdWarn", "外部纳管建议 host_id=0，当前非 0 仍可通过 Jedis 采集");
            }
            String password = appDesc.getAppPassword();
            try {
                String pong = jedisPing(inst.getIp(), inst.getPort(), password);
                node.put("ping", pong != null ? pong.trim() : "null");
            } catch (Exception e) {
                allOk = false;
                node.put("pingError", e.getMessage());
            }
            try {
                String info = jedisInfoAll(inst.getIp(), inst.getPort(), password);
                node.put("infoBytes", info != null ? info.length() : 0);
                node.put("infoHasServerSection", info != null && info.contains("# Server"));
                node.put("infoOk", true);
            } catch (Exception e) {
                allOk = false;
                node.put("infoError", e.getMessage());
            }
            Map<?, ?> infoMap = redisCenter.collectRedisInfo(appId, collectTime, inst.getIp(), inst.getPort());
            node.put("collectInfoMapOk", infoMap != null && !infoMap.isEmpty());
            if (infoMap == null || infoMap.isEmpty()) {
                allOk = false;
                node.put("collectHint", "collectRedisInfo 返回空，看日志 jedis infoMap is null");
            }
            InstanceStats stats = instanceStatsDao.getInstanceStatsByHost(inst.getIp(), inst.getPort());
            if (stats != null) {
                node.put("instanceStatistics", "有数据 usedMemory=" + stats.getUsedMemory() + " conn="
                        + stats.getCurrConnections());
            } else {
                allOk = false;
                node.put("instanceStatistics", "无记录（页面全局信息会显示 0）");
            }
            StandardStats ss = standardStatsDao.getStandardStats(collectTime, inst.getIp(), inst.getPort(),
                    ConstUtils.REDIS);
            node.put("standardStatisticsThisMinute", ss != null ? "有" : "无（首分钟可能正常）");
            nodes.add(node);
        }
        result.put("nodes", nodes);
        result.put("ok", allOk);
        result.put("hint", allOk
                ? "采集链路正常，请刷新应用统计页；命令曲线需连续采集 2 分钟以上"
                : "请根据 nodes 里 pingError/infoError/collectHint 排查");
        return result;
    }

    /**
     * 纳管完成后立即采一次，让页面不必等到下一个采集周期才有数据。
     *
     * <p>原先这里还会调用 brevityScheduler.maintainTasks() 往 brevity_schedule_resources 注册节点，
     * 但消费该表的 dispatcherTasks() 已无任何调度调用方，注册纯属空转，随该机制一并移除。
     * 分钟级采集由 ExternalRedisStatsCollectJob 承担。
     */
    private void registerScheduleAndCollect(long appId, int type) {
        logger.info("external redis registerScheduleAndCollect appId={} type={}", appId, type);
        triggerInitialCollect(appId, type);
    }

    private void triggerInitialCollect(long appId, int type) {
        try {
            long collectTime = Long.parseLong(DateUtil.formatDate(new Date(), "yyyyMMddHHmm"));
            List<InstanceInfo> instances = instanceDao.getInstListByAppId(appId);
            if (instances == null) {
                return;
            }
            int ok = 0;
            int fail = 0;
            for (InstanceInfo instance : instances) {
                try {
                    Map<?, ?> infoMap = redisCenter.collectRedisInfo(appId, collectTime, instance.getIp(),
                            instance.getPort());
                    if (!TypeUtil.isRedisSentinel(instance.getType())) {
                        redisCenter.collectRedisSlowLog(appId, collectTime, instance.getIp(), instance.getPort());
                        redisCenter.collectRedisLatencyInfo(appId, collectTime, instance.getIp(), instance.getPort());
                    }
                    if (infoMap == null || infoMap.isEmpty()) {
                        fail++;
                        logger.warn("external redis collect empty appId={} {}:{}", appId, instance.getIp(),
                                instance.getPort());
                    } else {
                        ok++;
                        logger.info("external redis collect ok appId={} {}:{}", appId, instance.getIp(),
                                instance.getPort());
                    }
                } catch (Exception ex) {
                    fail++;
                    logger.warn("external redis collect error appId={} {}:{} {}", appId, instance.getIp(),
                            instance.getPort(), ex.getMessage());
                }
            }
            logger.info("external redis initial collect finished appId={} ok={} fail={}", appId, ok, fail);
        } catch (Exception e) {
            logger.warn("external redis initial collect failed appId={}: {}", appId, e.getMessage());
        }
    }

    private ImportAppResult validateAppType(int type) {
        if (type == ConstUtils.CACHE_REDIS_STANDALONE
                || type == ConstUtils.CACHE_REDIS_SENTINEL
                || type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            return ImportAppResult.success();
        }
        return ImportAppResult.fail("请选择集群类型（单机 / 哨兵 / Cluster），当前 type=" + type + " 无效");
    }

    private boolean isSentinelInstanceLine(String memoryOrMasterName) {
        return StringUtils.isNotBlank(memoryOrMasterName) && !NumberUtils.isDigits(memoryOrMasterName);
    }

    private boolean isDataInstanceLine(String memoryOrMasterName) {
        return NumberUtils.isDigits(memoryOrMasterName) && NumberUtils.toInt(memoryOrMasterName) >= 0;
    }

    private int resolveDataInstanceType(int appType) {
        if (appType == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            return ConstUtils.CACHE_TYPE_REDIS_CLUSTER;
        }
        return ConstUtils.CACHE_REDIS_STANDALONE;
    }

    /**
     * 历史纳管可能把 type 写成 0，根据 instance_info 文本推断哨兵/单机。
     */
    private int resolveEffectiveAppType(int type, String appInstanceInfo) {
        if (type == ConstUtils.CACHE_REDIS_STANDALONE
                || type == ConstUtils.CACHE_REDIS_SENTINEL
                || type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            return type;
        }
        if (StringUtils.isBlank(appInstanceInfo)) {
            return ConstUtils.CACHE_REDIS_STANDALONE;
        }
        String normalized = appInstanceInfo.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n")) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            String[] parts = line.trim().split(":");
            if (parts.length >= 3 && isSentinelInstanceLine(parts[2].trim())) {
                return ConstUtils.CACHE_REDIS_SENTINEL;
            }
        }
        return ConstUtils.CACHE_REDIS_STANDALONE;
    }

    private boolean looksLikeMemoryMb(int value) {
        return value == 512 || value == 1024 || value == 2048 || value == 4096 || value == 8192
                || value == 16384;
    }

    private String getSentinelMasterName(final String ip, final int port, final String password) {
        final StringBuilder masterName = new StringBuilder();
        new IdempotentConfirmer() {
            private int timeOutFactor = 1;

            @Override
            public boolean execute() {
                Jedis jedis = null;
                try {
                    if (StringUtils.isNotBlank(password)) {
                        jedis = redisCenter.getJedis(ip, port, password);
                    } else {
                        jedis = redisCenter.getJedis(ip, port);
                    }
                    jedis.getClient().setConnectionTimeout(Protocol.DEFAULT_TIMEOUT * (timeOutFactor++));
                    jedis.getClient().setSoTimeout(Protocol.DEFAULT_TIMEOUT * (timeOutFactor++));
                    List<Map<String, String>> mapList = jedis.sentinelMasters();
                    String targetKey = "name";
                    for (Map<String, String> map : mapList) {
                        if (map.containsKey(targetKey)) {
                            masterName.append(MapUtils.getString(map, targetKey, ""));
                        }
                    }
                    return true;
                } catch (Exception e) {
                    logger.warn("{}:{} error message is {} ", ip, port, e.getMessage());
                    return false;
                } finally {
                    if (jedis != null) {
                        jedis.close();
                    }
                }
            }
        }.run();
        return masterName.toString();
    }

    @Override
    public List<InstanceIpSearchVO> searchByInstanceIp(String ipQuery) {
        if (StringUtils.isBlank(ipQuery)) {
            return Collections.emptyList();
        }
        String query = ipQuery.trim();
        String ip;
        Integer port = null;
        int colon = query.lastIndexOf(':');
        if (colon > 0 && colon < query.length() - 1) {
            String portPart = query.substring(colon + 1).trim();
            if (NumberUtils.isDigits(portPart)) {
                ip = query.substring(0, colon).trim();
                port = Integer.parseInt(portPart);
            } else {
                ip = query;
            }
        } else {
            ip = query;
        }
        if (StringUtils.isBlank(ip)) {
            return Collections.emptyList();
        }
        String ipPattern = escapeSqlLike(ip);

        List<InstanceInfo> instances;
        if (port != null) {
            instances = instanceDao.getInstListByIpLikeAndPort(ipPattern, port);
            if (instances == null || instances.isEmpty()) {
                InstanceInfo one = instanceDao.getAllInstByIpAndPort(ip, port);
                instances = one != null ? Collections.singletonList(one) : Collections.<InstanceInfo>emptyList();
            }
        } else {
            instances = instanceDao.getInstListByIpLike(ipPattern);
            if (instances == null || instances.isEmpty()) {
                instances = instanceDao.getInstListByIp(ip);
            }
            if (instances == null) {
                instances = Collections.emptyList();
            }
        }

        Map<Long, InstanceIpSearchVO> byAppId = new LinkedHashMap<Long, InstanceIpSearchVO>();
        for (InstanceInfo inst : instances) {
            if (inst == null || inst.getAppId() <= 0) {
                continue;
            }
            long appId = inst.getAppId();
            InstanceIpSearchVO vo = byAppId.get(appId);
            if (vo == null) {
                vo = new InstanceIpSearchVO();
                vo.setAppId(appId);
                AppDesc appDesc = appService.getByAppId(appId);
                if (appDesc != null) {
                    vo.setAppName(appDesc.getName());
                    vo.setTypeDesc(resolveTypeDesc(appDesc.getType()));
                } else {
                    vo.setAppName("应用#" + appId);
                    vo.setTypeDesc("");
                }
                vo.setExternalManaged(externalRedisDao.getByAppId(appId) != null);
                byAppId.put(appId, vo);
            }
            vo.getMatchedNodes().add(inst.getIp() + ":" + inst.getPort());
        }
        return new ArrayList<InstanceIpSearchVO>(byAppId.values());
    }

    /**
     * 外部纳管集群不绑定平台 Redis 安装包资源。
     * 这里只读取实例 INFO 里的 redis_version，用于提示和展示；app_desc.version_id 固定写 0。
     */
    private RedisVersionResolution resolveRedisVersion(int versionId, int type, String appInstanceInfo,
                                                       String password) {
        String detectedVersion = detectRedisVersion(type, appInstanceInfo, password);
        if (StringUtils.isBlank(detectedVersion)) {
            throw new IllegalStateException("无法从 Redis 实例读取版本，请检查节点连通性、密码以及 INFO 命令权限");
        }
        logger.info("external redis detected redis_version={}, no system_resource binding required", detectedVersion);
        return new RedisVersionResolution(0, "redis-" + detectedVersion);
    }

    private String detectRedisVersion(int type, String appInstanceInfo, String password) {
        if (StringUtils.isBlank(appInstanceInfo)) {
            return "";
        }
        String normalized = appInstanceInfo.replace("\r\n", "\n").replace('\r', '\n');
        for (String rawLine : normalized.split("\n")) {
            if (StringUtils.isBlank(rawLine)) {
                continue;
            }
            String line = rawLine.trim();
            String[] parts = line.split(":");
            if (parts.length < 2) {
                continue;
            }
            if (type == ConstUtils.CACHE_REDIS_SENTINEL
                    && parts.length >= 3
                    && isSentinelInstanceLine(parts[2].trim())) {
                continue;
            }
            String ip = parts[0].trim();
            int port = NumberUtils.toInt(parts[1].trim());
            if (StringUtils.isBlank(ip) || port <= 0) {
                continue;
            }
            String version = queryRedisVersion(ip, port, password);
            if (StringUtils.isNotBlank(version)) {
                return version;
            }
        }
        return "";
    }

    @Override
    public String detectRedisVersionName(int type, String appInstanceInfo, String password) {
        String version = detectRedisVersion(type, appInstanceInfo, password);
        return StringUtils.isBlank(version) ? "" : "redis-" + version;
    }

    private String queryRedisVersion(String ip, int port, String password) {
        Jedis jedis = null;
        try {
            jedis = openJedis(ip, port, password);
            String version = parseRedisInfoValue(jedis.info("server"), "redis_version");
            if (StringUtils.isBlank(version)) {
                version = parseRedisInfoValue(jedis.info("all"), "redis_version");
            }
            return StringUtils.trimToEmpty(version);
        } catch (Exception e) {
            logger.warn("detect redis version failed {}:{} {}", ip, port, e.getMessage());
            return "";
        } finally {
            closeJedis(jedis);
        }
    }

    private String parseRedisInfoValue(String info, String key) {
        if (StringUtils.isBlank(info) || StringUtils.isBlank(key)) {
            return "";
        }
        String prefix = key + ":";
        String normalized = info.replace("\r\n", "\n").replace('\r', '\n');
        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static class RedisVersionResolution {
        private final int resourceId;
        private final String versionName;

        RedisVersionResolution(int resourceId, String versionName) {
            this.resourceId = resourceId;
            this.versionName = versionName;
        }

        int getResourceId() {
            return resourceId;
        }

        String getVersionName() {
            return versionName;
        }
    }

    /** 转义 LIKE 通配符，避免用户输入 % _ 影响查询 */
    private String escapeSqlLike(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String resolveTypeDesc(int type) {
        if (type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            return "redis-cluster";
        } else if (type == ConstUtils.CACHE_REDIS_SENTINEL) {
            return "redis-sentinel";
        } else if (type == ConstUtils.CACHE_REDIS_STANDALONE) {
            return "redis-standalone";
        }
        return String.valueOf(type);
    }
}
