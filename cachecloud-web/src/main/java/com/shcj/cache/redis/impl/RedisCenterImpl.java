package com.shcj.cache.redis.impl;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.shcj.cache.async.AsyncService;
import com.shcj.cache.async.AsyncThreadPoolFactory;
import com.shcj.cache.async.KeyCallable;
import com.shcj.cache.constant.*;
import com.shcj.cache.dao.*;
import com.shcj.cache.entity.*;
import com.shcj.cache.exception.SSHException;
import com.shcj.cache.machine.MachineCenter;
import com.shcj.cache.protocol.MachineProtocol;
import com.shcj.cache.protocol.RedisProtocol;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.redis.command.RedisNativeCommandService;
import com.shcj.cache.redis.enums.RedisInfoEnum;
import com.shcj.cache.risk.collect.RiskMetricCollector;
import com.shcj.cache.redis.enums.RedisReadOnlyCommandEnum;
import com.shcj.cache.redis.util.*;
import com.shcj.cache.report.ReportDataComponent;
import com.shcj.cache.ssh.SSHService;
import com.shcj.cache.ssh.SSHUtil;
import com.shcj.cache.stats.instance.InstanceStatsCenter;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.InstanceInfoEnum.InstanceTypeEnum;
import com.shcj.cache.util.*;
import com.shcj.cache.web.enums.BooleanEnum;
import com.shcj.cache.web.enums.ClientTypeEnum;
import com.shcj.cache.web.enums.SuccessEnum;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.service.ModuleService;
import com.shcj.cache.web.service.WebClientComponent;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.RedisSlowLog;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import redis.clients.jedis.*;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisAskDataException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisMovedDataException;
import redis.clients.jedis.util.SafeEncoder;
import redis.clients.jedis.util.Slowlog;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Created by yijunzhang on 14-6-10.
 */
@Service("redisCenter")
public class RedisCenterImpl implements RedisCenter {
    public static final int REDIS_DEFAULT_TIME = 1000;

    /** 慢日志入库遇到死锁时的重试次数 */
    private static final int SLOW_LOG_SAVE_MAX_ATTEMPTS = 3;

    private static final long SLOW_LOG_RETRY_BASE_MILLIS = 50L;

    /** MySQL 死锁的错误码 */
    private static final int MYSQL_DEADLOCK_ERROR_CODE = 1213;
    public static final String REDIS_SLOWLOG_POOL = "redis-slowlog-pool";
    private static final int COUNT = 1000;
    private static final long CLIENT_LIST_CACHE_TTL_MS = 30_000L;
    private static List<RedisInfoEnum> otherNeedCalDifRedisInfoEnumList = new ArrayList<>();
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Lock lock = new ReentrantLock();
    @Value("${cachecloud.redis.managed-loopback-host:127.0.0.1}")
    private String managedLoopbackHost;
    @Autowired
    private AppStatsDao appStatsDao;
    @Autowired
    private AsyncService asyncService;
    @Autowired
    private InstanceDao instanceDao;
    @Autowired
    private InstanceStatsDao instanceStatsDao;
    @Autowired
    private InstanceStatsCenter instanceStatsCenter;
    @Autowired
    @Lazy
    private MachineCenter machineCenter;
    private volatile Map<String, JedisPool> jedisPoolMap = new HashMap<String, JedisPool>();
    /** 建池时用的密码，用于识别集群改密后需要重建连接池，见 maintainJedisPool */
    private final Map<String, String> jedisPoolPasswordMap = new HashMap<String, String>();
    @Autowired
    private AppDao appDao;
    @Autowired
    private ExternalRedisDao externalRedisDao;
    @Autowired
    private MachineDao machineDao;
    @Autowired
    private RedisModuleConfigDao redisModuleConfigDao;
    @Autowired
    private AppAuditLogDao appAuditLogDao;
    @Autowired
    private AppService appService;
    @Autowired
    private InstanceSlowLogDao instanceSlowLogDao;
    @Autowired
    private RiskMetricCollector riskMetricCollector;
    @Autowired
    private InstanceLatencyHistoryDao instanceLatencyHistoryDao;
    @Autowired
    private WebClientComponent webClientComponent;
    @Autowired
    SSHService sshService;
    @Autowired
    private ModuleService moduleService;

    @Autowired
    private ReportDataComponent reportDataComponent;


    @Autowired
    private RedisNativeCommandService redisNativeCommandService;

    private final ConcurrentHashMap<Integer, ClientListCacheEntry> clientListCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        asyncService.assemblePool(getThreadPoolKey(), AsyncThreadPoolFactory.REDIS_SLOWLOG_THREAD_POOL);
        otherNeedCalDifRedisInfoEnumList.add(RedisInfoEnum.mem_fragmentation_ratio);
        otherNeedCalDifRedisInfoEnumList.add(RedisInfoEnum.used_cpu_sys);
        otherNeedCalDifRedisInfoEnumList.add(RedisInfoEnum.used_cpu_user);
        otherNeedCalDifRedisInfoEnumList.add(RedisInfoEnum.used_cpu_sys_children);
        otherNeedCalDifRedisInfoEnumList.add(RedisInfoEnum.used_cpu_user_children);
    }


    private JedisPool maintainJedisPool(String host, int port, String password) {
        String connectionHost = RedisHostResolver.resolve(managedLoopbackHost, host);
        String hostAndPort = ObjectConvert.linkIpAndPort(connectionHost, port);
        JedisPool jedisPool = jedisPoolMap.get(hostAndPort);
        // 池只按 host:port 缓存，密码仅在建池那一次生效。集群改密之后这里会一直拿着旧密码的池，
        // 拓扑同步的 getMaster/getSlave0 就持续 NOAUTH，直到重启才恢复——密码变了必须重建。
        if (jedisPool != null && !StringUtils.equals(jedisPoolPasswordMap.get(hostAndPort), password)) {
            lock.lock();
            try {
                if (!StringUtils.equals(jedisPoolPasswordMap.get(hostAndPort), password)) {
                    JedisPool stale = jedisPoolMap.remove(hostAndPort);
                    jedisPoolPasswordMap.remove(hostAndPort);
                    if (stale != null) {
                        try {
                            stale.destroy();
                        } catch (Exception e) {
                            logger.warn("destroy stale jedisPool {} failed: {}", hostAndPort, e.getMessage());
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
            jedisPool = jedisPoolMap.get(hostAndPort);
        }
        if (jedisPool == null) {
            lock.lock();
            try {
                //double check
                jedisPool = jedisPoolMap.get(hostAndPort);
                if (jedisPool == null) {
                    try {
                        if (StringUtils.isNotBlank(password)) {
                            jedisPool = new JedisPool(new GenericObjectPoolConfig(), connectionHost, port,
                                    Protocol.DEFAULT_TIMEOUT, password);
                        } else {
                            jedisPool = new JedisPool(new GenericObjectPoolConfig(), connectionHost, port,
                                    Protocol.DEFAULT_TIMEOUT);
                        }
                        jedisPoolMap.put(hostAndPort, jedisPool);
                        jedisPoolPasswordMap.put(hostAndPort, password);
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    } finally {

                    }
                }
            } finally {
                lock.unlock();
            }
        }
        return jedisPool;
    }

    private String buildFutureKey(long appId, long collectTime, String host, int port) {
        StringBuilder keyBuffer = new StringBuilder("redis-");
        keyBuffer.append(collectTime);
        keyBuffer.append("-");
        keyBuffer.append(appId);
        keyBuffer.append("-");
        keyBuffer.append(host + ":" + port);
        return keyBuffer.toString();
    }

    @Override
    public List<InstanceSlowLog> collectRedisSlowLog(long appId, long collectTime, String host, int port) {
        Assert.isTrue(appId >= 0);
        Assert.hasText(host);
        Assert.isTrue(port > 0);
        // getInstByIpAndPort 只返回 status=1 的节点；存活探测以采集结果为准，
        // 心跳停止的节点必须继续采集，否则无法发现恢复
        InstanceInfo instanceInfo = instanceDao.getAllInstByIpAndPort(host, port);
        //不存在实例/已下线
        if (instanceInfo == null || !instanceInfo.isCollectable()) {
            return null;
        }
        if (TypeUtil.isRedisSentinel(instanceInfo.getType())) {
            //忽略sentinel redis实例
            return null;
        }
        // 从redis中获取慢查询日志
        List<RedisSlowLog> redisLowLogList = getRedisSlowLogs(appId, host, port, 100);
        if (CollectionUtils.isEmpty(redisLowLogList)) {
            return Collections.emptyList();
        }

        // transfer
        // SLOWLOG 缓冲区在 RESET 之前不会清空，每分钟拿到的都是同一批条目；这里按已入库的
        // 最新时间过滤掉旧条目，否则每轮都会对同样的行做一次重复写入
        Timestamp lastExecuteTime = getLastSlowLogExecuteTime(instanceInfo.getId());
        final List<InstanceSlowLog> instanceSlowLogList = new ArrayList<InstanceSlowLog>();
        for (RedisSlowLog redisSlowLog : redisLowLogList) {
            InstanceSlowLog instanceSlowLog = transferRedisSlowLogToInstance(redisSlowLog, instanceInfo);
            if (instanceSlowLog == null) {
                continue;
            }
            // 同秒条目可能有多条，用 >= 保证不漏，剩下的重复交给 insert ignore
            if (lastExecuteTime != null && instanceSlowLog.getExecuteTime() != null
                    && instanceSlowLog.getExecuteTime().before(lastExecuteTime)) {
                continue;
            }
            instanceSlowLogList.add(instanceSlowLog);
        }

        if (CollectionUtils.isEmpty(instanceSlowLogList)) {
            return Collections.emptyList();
        }
        // 并发批量插入同一张表时，按唯一键排序可以让各批次的加锁顺序一致，避免互相等待成环
        instanceSlowLogList.sort(Comparator.comparingLong(InstanceSlowLog::getInstanceId)
                .thenComparingLong(InstanceSlowLog::getSlowLogId)
                .thenComparing(InstanceSlowLog::getExecuteTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())));

        //处理
        String key = getThreadPoolKey() + "_" + host + "_" + port;
        boolean isOk = asyncService.submitFuture(getThreadPoolKey(), new KeyCallable<Boolean>(key) {
            @Override
            public Boolean execute() {
                return batchSaveWithRetry(instanceSlowLogList);
            }
        });
        if (!isOk) {
            logger.error("slowlog submitFuture failed,appId:{},collectTime:{},host:{},port:{}", appId, collectTime,
                    host, port);
        }
        return instanceSlowLogList;
    }

    /** 查询失败不应阻断采集：拿不到基准时间就退回全量写入，由 insert ignore 兜底 */
    /**
     * 慢日志入库，死锁时重试。
     *
     * <p>instance_slow_log 上有 (instance_id, slow_log_id, execute_time) 的唯一索引，
     * insert ignore 为了判重会在唯一索引上加间隙锁。采集改成并发之后，多个实例的慢日志
     * 批次同时写入，两个事务按不同顺序拿锁就会死锁——MySQL 回滚其中一个并明确建议重试。
     *
     * <p>不重试的话这一批慢日志会被静默丢掉：异常在这里被吞，采集看起来一切正常，
     * 只是慢查询页面少了几条记录，事后根本无从发现。</p>
     */
    private boolean batchSaveWithRetry(List<InstanceSlowLog> instanceSlowLogList) {
        for (int attempt = 1; attempt <= SLOW_LOG_SAVE_MAX_ATTEMPTS; attempt++) {
            try {
                instanceSlowLogDao.batchSave(instanceSlowLogList);
                return true;
            } catch (Exception e) {
                if (!isDeadlock(e) || attempt == SLOW_LOG_SAVE_MAX_ATTEMPTS) {
                    logger.error("save slow log failed after {} attempt(s): {}", attempt, e.getMessage(), e);
                    return false;
                }
                logger.warn("save slow log deadlock, retry {}/{}", attempt, SLOW_LOG_SAVE_MAX_ATTEMPTS);
                try {
                    // 错开重试时刻，两个事务同时重试只会再撞一次
                    Thread.sleep(SLOW_LOG_RETRY_BASE_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private boolean isDeadlock(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException
                    && ((java.sql.SQLException) cause).getErrorCode() == MYSQL_DEADLOCK_ERROR_CODE) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains("Deadlock found")) {
                return true;
            }
        }
        return false;
    }

    private Timestamp getLastSlowLogExecuteTime(long instanceId) {
        try {
            return instanceSlowLogDao.getMaxExecuteTimeByInstanceId(instanceId);
        } catch (Exception e) {
            logger.warn("query last slow log execute time failed, instanceId={}: {}", instanceId, e.getMessage());
            return null;
        }
    }

    private InstanceSlowLog transferRedisSlowLogToInstance(RedisSlowLog redisSlowLog, InstanceInfo instanceInfo) {
        if (redisSlowLog == null) {
            return null;
        }
        String command = redisSlowLog.getCommand();
        long executionTime = redisSlowLog.getExecutionTime();
        //如果command=BGREWRITEAOF并且小于50毫秒,则忽略
        if ("BGREWRITEAOF".equalsIgnoreCase(command) && executionTime < 50000) {
            return null;
        }
        InstanceSlowLog instanceSlowLog = new InstanceSlowLog();
        instanceSlowLog.setAppId(instanceInfo.getAppId());
        instanceSlowLog.setCommand(redisSlowLog.getCommand());
        instanceSlowLog.setClientIp(redisSlowLog.getClientIp());
        instanceSlowLog.setCostTime((int) redisSlowLog.getExecutionTime());
        instanceSlowLog.setCreateTime(new Timestamp(System.currentTimeMillis()));
        instanceSlowLog.setExecuteTime(new Timestamp(redisSlowLog.getDate().getTime()));
        instanceSlowLog.setInstanceId(instanceInfo.getId());
        instanceSlowLog.setIp(instanceInfo.getIp());
        instanceSlowLog.setPort(instanceInfo.getPort());
        instanceSlowLog.setSlowLogId(redisSlowLog.getId());

        return instanceSlowLog;
    }

    private String getThreadPoolKey() {
        return REDIS_SLOWLOG_POOL;
    }

    @Override
    public Map<RedisConstant, Map<String, Object>> collectRedisInfo(long appId, long collectTime, String host,
                                                                    int port) {
        long start = System.currentTimeMillis();

        // getInstByIpAndPort 只返回 status=1 的节点；存活探测以采集结果为准，
        // 心跳停止的节点必须继续采集，否则无法发现恢复
        InstanceInfo instanceInfo = instanceDao.getAllInstByIpAndPort(host, port);
        //不存在实例/已下线
        if (instanceInfo == null || !instanceInfo.isCollectable()) {
            return null;
        }
        if (TypeUtil.isRedisSentinel(instanceInfo.getType())) {
            //忽略sentinel redis实例
            return null;
        }
        Map<RedisConstant, Map<String, Object>> infoMap = this.getInfoStats(appId, host, port);
        if (infoMap == null || infoMap.isEmpty()) {
            logger.error("appId:{},collectTime:{},host:{},port:{} cost={} ms redis infoMap is null",
                    new Object[]{appId, collectTime, host, port, (System.currentTimeMillis() - start)});
            return infoMap;
        }

        //上报数据
        Map<String, Object> redisInfoMap = new HashMap<>();
        redisInfoMap.put("instanceInfo", instanceInfo);
        redisInfoMap.put("collectTime", collectTime);
        redisInfoMap.put("info", infoMap);
        reportDataComponent.reportRedisInfoData(redisInfoMap);

        // cluster info统计
        Map<String, Object> clusterInfoMap = getClusterInfoStats(appId, instanceInfo);

        boolean isOk = asyncService
                .submitFuture(new RedisKeyCallable(appId, collectTime, host, port, infoMap, clusterInfoMap));
        if (!isOk) {
            logger.error("submitFuture failed,appId:{},collectTime:{},host:{},port:{} cost={} ms",
                    new Object[]{appId, collectTime, host, port, (System.currentTimeMillis() - start)});
        }
        return infoMap;
    }

    @Override
    public Map<RedisConstant, Map<String, Object>> getInfoStats(final long appId, final String host, final int port) {
        Map<RedisConstant, Map<String, Object>> infoMap = null;
        final StringBuilder infoBuilder = new StringBuilder();
        try {
            boolean isOk = new IdempotentConfirmer() {
                private int timeOutFactor = 1;

                @Override
                public boolean execute() {
                    Jedis jedis = null;
                    try {
                        jedis = getJedis(appId, host, port);  //todo zoushunqing
                        jedis.getClient().setConnectionTimeout(REDIS_DEFAULT_TIME * (timeOutFactor++));
                        jedis.getClient().setSoTimeout(REDIS_DEFAULT_TIME * (timeOutFactor++));
                        String info = jedis.info("all");
                        infoBuilder.append(info);
                        return StringUtils.isNotBlank(info);
                    } catch (Exception e) {
                        logger.warn("{}:{}, redis-getInfoStats errorMsg:{}", host, port, e.getMessage());
                        return false;
                    } finally {
                        if (jedis != null) {
                            jedis.close();
                        }
                    }
                }
            }.run();
            if (!isOk) {
                return infoMap;
            }
            infoMap = processRedisStats(infoBuilder.toString());
        } catch (Exception e) {
            logger.error(e.getMessage() + " {}:{}", host, port, e);
        }
        if (infoMap == null || infoMap.isEmpty()) {
            logger.error("host:{},port:{} redis infoMap is null", host, port);
            return infoMap;
        }
        return infoMap;
    }

    @Override
    public Map<String, Object> getClusterInfoStats(final long appId, final String host, final int port) {
        InstanceInfo instanceInfo = instanceDao.getAllInstByIpAndPort(host, port);
        return getClusterInfoStats(appId, instanceInfo);
    }

    @Override
    public Map<String, Object> getClusterInfoStats(final long appId, final InstanceInfo instanceInfo) {
        long startTime = System.currentTimeMillis();
        if (instanceInfo == null) {
            logger.warn("getClusterInfoStats instanceInfo is null");
            return Collections.emptyMap();
        }
        if (!TypeUtil.isRedisCluster(instanceInfo.getType())) {
            return Collections.emptyMap();
        }
        final String host = instanceInfo.getIp();
        final int port = instanceInfo.getPort();
        Map<String, Object> clusterInfoMap = null;
        final StringBuilder infoBuilder = new StringBuilder();
        try {
            boolean isOk = new IdempotentConfirmer() {
                private int timeOutFactor = 1;

                @Override
                public boolean execute() {
                    Jedis jedis = null;
                    try {
                        jedis = getJedis(appId, host, port);
                        jedis.getClient().setConnectionTimeout(REDIS_DEFAULT_TIME * (timeOutFactor++));
                        jedis.getClient().setSoTimeout(REDIS_DEFAULT_TIME * (timeOutFactor++));
                        String clusterInfo = jedis.clusterInfo();
                        infoBuilder.append(clusterInfo);
                        return StringUtils.isNotBlank(clusterInfo);
                    } catch (Exception e) {
                        logger.warn("{}:{}, redis-getInfoStats errorMsg:{}", host, port, e.getMessage());
                        return false;
                    } finally {
                        if (jedis != null) {
                            jedis.close();
                        }
                    }
                }
            }.run();
            if (!isOk) {
                return clusterInfoMap;
            }
            clusterInfoMap = processClusterInfoStats(infoBuilder.toString());
        } catch (Exception e) {
            logger.error(e.getMessage() + " {}:{}", host, port, e);
        }
        if (MapUtils.isEmpty(clusterInfoMap)) {
            logger.error("{}:{} redis clusterInfoMap is null", host, port);
            return Collections.emptyMap();
        }
        long costTime = System.currentTimeMillis() - startTime;
        if (costTime > 1000) {
            logger.warn("{}:{} cluster info cost time {} ms", host, port, costTime);
        }
        return clusterInfoMap;
    }

    private void fillAccumulationMap(Map<RedisConstant, Map<String, Object>> infoMap,
                                     Table<RedisConstant, String, Long> table) {
        if (table == null || table.isEmpty()) {
            return;
        }
        Map<String, Object> accMap = infoMap.get(RedisConstant.DIFF);
        if (accMap == null) {
            accMap = new LinkedHashMap<String, Object>();
            infoMap.put(RedisConstant.DIFF, accMap);
        }
        for (RedisConstant constant : table.rowKeySet()) {
            Map<String, Long> rowMap = table.row(constant);
            accMap.putAll(rowMap);
        }
    }

    private void fillDoubleAccumulationMap(Map<RedisConstant, Map<String, Object>> infoMap,
                                           Table<RedisConstant, String, Double> table) {
        if (table == null || table.isEmpty()) {
            return;
        }
        Map<String, Object> accMap = infoMap.get(RedisConstant.DIFF);
        if (accMap == null) {
            accMap = new LinkedHashMap<String, Object>();
            infoMap.put(RedisConstant.DIFF, accMap);
        }
        for (RedisConstant constant : table.rowKeySet()) {
            Map<String, Double> rowMap = table.row(constant);
            accMap.putAll(rowMap);
        }
    }

    /**
     * 内存碎片率统计最新值，不计算差值
     */
    private void fillMemFragRatioMap(Map<RedisConstant, Map<String, Object>> infoMap) {

        Map<String, Double> currentMap = new LinkedHashMap<>();
        RedisInfoEnum acc = RedisInfoEnum.mem_fragmentation_ratio;
        Double count = getDoubleCount(infoMap, acc.getRedisConstant(), acc.getValue());
        if (count != null) {
            currentMap.put(acc.getValue(), count);
        }
        //DecimalFormat df = new DecimalFormat("##.##");
        Map<String, Object> accMap = infoMap.get(RedisConstant.DIFF);
        if (accMap == null) {
            accMap = new LinkedHashMap<>();
            infoMap.put(RedisConstant.DIFF, accMap);
        }
        accMap.putAll(currentMap);
    }

    /**
     * 运维关键指标快照写入分钟 diff，供应用级图表聚合（类似 memFragRatio）。
     */
    private void fillOpsSnapshotMap(Map<RedisConstant, Map<String, Object>> infoMap) {
        Map<String, Object> accMap = infoMap.get(RedisConstant.DIFF);
        if (accMap == null) {
            accMap = new LinkedHashMap<String, Object>();
            infoMap.put(RedisConstant.DIFF, accMap);
        }

        Long ops = getCommonCount(infoMap, RedisConstant.Stats, RedisInfoEnum.instantaneous_ops_per_sec.getValue());
        if (ops != null) {
            accMap.put(RedisInfoEnum.instantaneous_ops_per_sec.getValue(), ops);
        }

        Long forkUsec = getCommonCount(infoMap, RedisConstant.Stats, RedisInfoEnum.latest_fork_usec.getValue());
        if (forkUsec == null) {
            forkUsec = getCommonCount(infoMap, RedisConstant.Persistence, RedisInfoEnum.latest_fork_usec.getValue());
        }
        if (forkUsec != null) {
            accMap.put(RedisInfoEnum.latest_fork_usec.getValue(), forkUsec);
        }

        // 上一次 RDB / AOF 重写的写盘耗时，是快照不是增量：-1 表示从未执行过，
        // 直接落库会在图上画出一条 -1 的横线，这里丢掉不采。
        Long rdbTime = getCommonCount(infoMap, RedisConstant.Persistence,
                RedisInfoEnum.rdb_last_bgsave_time_sec.getValue());
        if (rdbTime != null && rdbTime >= 0) {
            accMap.put(RedisInfoEnum.rdb_last_bgsave_time_sec.getValue(), rdbTime);
        }
        Long aofRewriteTime = getCommonCount(infoMap, RedisConstant.Persistence,
                RedisInfoEnum.aof_last_rewrite_time_sec.getValue());
        if (aofRewriteTime != null && aofRewriteTime >= 0) {
            accMap.put(RedisInfoEnum.aof_last_rewrite_time_sec.getValue(), aofRewriteTime);
        }

        accMap.put("repl_lag_max", resolveReplLagMax(infoMap));
        accMap.put("repl_link_down", resolveReplLinkDown(infoMap));

        Long aofCurrentSize = getCommonCount(infoMap, RedisConstant.Persistence, RedisInfoEnum.aof_current_size.getValue());
        if (aofCurrentSize != null) {
            accMap.put(RedisInfoEnum.aof_current_size.getValue(), aofCurrentSize);
        }
        Long aofBaseSize = getCommonCount(infoMap, RedisConstant.Persistence, RedisInfoEnum.aof_base_size.getValue());
        if (aofBaseSize != null) {
            accMap.put(RedisInfoEnum.aof_base_size.getValue(), aofBaseSize);
        }
        Long masterReplOffset = getCommonCount(infoMap, RedisConstant.Replication, RedisInfoEnum.master_repl_offset.getValue());
        if (masterReplOffset != null) {
            accMap.put(RedisInfoEnum.master_repl_offset.getValue(), masterReplOffset);
        }

        Long usedMemory = getCommonCount(infoMap, RedisConstant.Memory, RedisInfoEnum.used_memory.getValue());
        if (usedMemory != null) {
            accMap.put(RedisInfoEnum.used_memory.getValue(), usedMemory);
        }
        Long usedMemoryRss = getCommonCount(infoMap, RedisConstant.Memory, RedisInfoEnum.used_memory_rss.getValue());
        if (usedMemoryRss != null) {
            accMap.put(RedisInfoEnum.used_memory_rss.getValue(), usedMemoryRss);
        }
        Long connectedClients = getCommonCount(infoMap, RedisConstant.Clients,
                RedisInfoEnum.connected_clients.getValue());
        if (connectedClients != null) {
            accMap.put(RedisInfoEnum.connected_clients.getValue(), connectedClients);
        }
        accMap.put("object_size", getObjectSize(infoMap));

        // 实例角色随分钟 diff 一起落库。副本会把复制过来的写命令计进自己的
        // commandstats（主从三个节点上 cmdstat_setex 的计数完全相同），读/写命令
        // 统计按实例求和时，写命令因此被放大成副本数倍。查询侧据此只对 master 计写。
        Map<String, Object> roleMap = infoMap.get(RedisConstant.Replication);
        if (roleMap != null) {
            Object role = roleMap.get(RedisInfoEnum.role.getValue());
            if (role != null) {
                accMap.put(ConstUtils.INSTANCE_ROLE_MASTER,
                        "master".equalsIgnoreCase(String.valueOf(role)) ? 1L : 0L);
            }
        }
    }

    private long resolveReplLagMax(Map<RedisConstant, Map<String, Object>> infoMap) {
        Map<String, Object> replMap = infoMap.get(RedisConstant.Replication);
        if (replMap == null || replMap.isEmpty()) {
            return 0L;
        }
        int maxLag = 0;
        int slaveCount = MapUtils.getIntValue(replMap, RedisInfoEnum.connected_slaves.getValue(), 0);
        for (int i = 0; i < slaveCount; i++) {
            Object slaveInfo = replMap.get("slave" + i);
            if (slaveInfo == null) {
                continue;
            }
            String[] arr = slaveInfo.toString().split(",");
            for (String part : arr) {
                if (part.startsWith("lag=")) {
                    maxLag = Math.max(maxLag, NumberUtils.toInt(part.substring(4)));
                }
            }
        }
        return maxLag;
    }

    private long resolveReplLinkDown(Map<RedisConstant, Map<String, Object>> infoMap) {
        Map<String, Object> replMap = infoMap.get(RedisConstant.Replication);
        if (replMap == null || replMap.isEmpty()) {
            return 0L;
        }
        Object roleObj = replMap.get(RedisInfoEnum.role.getValue());
        if (roleObj != null && "slave".equalsIgnoreCase(roleObj.toString())) {
            Object linkObj = replMap.get(RedisInfoEnum.master_link_status.getValue());
            if (linkObj != null && !"up".equalsIgnoreCase(linkObj.toString())) {
                return 1L;
            }
            return 0L;
        }
        int down = 0;
        int slaveCount = MapUtils.getIntValue(replMap, RedisInfoEnum.connected_slaves.getValue(), 0);
        for (int i = 0; i < slaveCount; i++) {
            Object slaveInfo = replMap.get("slave" + i);
            if (slaveInfo == null || !slaveInfo.toString().contains("state=online")) {
                down++;
            }
        }
        return down;
    }

    /**
     * 获取累加参数值
     *
     * @param currentInfoMap
     * @return 累加差值map
     */
    private Table<RedisConstant, String, Long> getAccumulationDiff(
            Map<RedisConstant, Map<String, Object>> currentInfoMap,
            Map<String, Object> lastInfoMap) {
        //没有上一次统计快照，忽略差值统计
        if (lastInfoMap == null || lastInfoMap.isEmpty()) {
            return HashBasedTable.create();
        }
        Map<RedisInfoEnum, Long> currentMap = new LinkedHashMap<RedisInfoEnum, Long>();
        for (RedisInfoEnum acc : RedisInfoEnum.getNeedCalDifRedisInfoEnumList()) {
            Long count = getCommonCount(currentInfoMap, acc.getRedisConstant(), acc.getValue());
            if (count != null) {
                currentMap.put(acc, count);
            }
        }
        Map<RedisInfoEnum, Long> lastMap = new LinkedHashMap<RedisInfoEnum, Long>();
        for (RedisInfoEnum acc : RedisInfoEnum.getNeedCalDifRedisInfoEnumList()) {
            Long lastCount = getCommonCount(lastInfoMap, acc.getRedisConstant(), acc.getValue());
            if (lastCount != null) {
                lastMap.put(acc, lastCount);
            }
        }
        Table<RedisConstant, String, Long> resultTable = HashBasedTable.create();
        for (RedisInfoEnum key : currentMap.keySet()) {
            Long value = MapUtils.getLong(currentMap, key, null);
            Long lastValue = MapUtils.getLong(lastMap, key, null);
            if (value == null || lastValue == null) {
                //忽略
                continue;
            }
            long diff = 0L;
            if (value > lastValue) {
                diff = value - lastValue;
            }
            resultTable.put(key.getRedisConstant(), key.getValue(), diff);
        }
        return resultTable;
    }

    /**
     * 获取累加参数值
     *
     * @param currentInfoMap
     * @return 累加差值map
     */
    private Table<RedisConstant, String, Double> getDoubleAccumulationDiff(
            Map<RedisConstant, Map<String, Object>> currentInfoMap,
            Map<String, Object> lastInfoMap) {
        //没有上一次统计快照，忽略差值统计
        if (lastInfoMap == null || lastInfoMap.isEmpty()) {
            return HashBasedTable.create();
        }
        Map<RedisInfoEnum, Double> currentMap = new LinkedHashMap<RedisInfoEnum, Double>();
        for (RedisInfoEnum acc : otherNeedCalDifRedisInfoEnumList) {
            Double count = getDoubleCount(currentInfoMap, acc.getRedisConstant(), acc.getValue());
            if (count != null) {
                currentMap.put(acc, count);
            }
        }
        Map<RedisInfoEnum, Double> lastMap = new LinkedHashMap<RedisInfoEnum, Double>();
        for (RedisInfoEnum acc : otherNeedCalDifRedisInfoEnumList) {
            Double lastCount = getDoubleCount(lastInfoMap, acc.getRedisConstant(), acc.getValue());
            if (lastCount != null) {
                lastMap.put(acc, lastCount);
            }
        }
        Table<RedisConstant, String, Double> resultTable = HashBasedTable.create();
        for (RedisInfoEnum key : currentMap.keySet()) {
            Double value = MapUtils.getDouble(currentMap, key, null);
            Double lastValue = MapUtils.getDouble(lastMap, key, null);
            if (value == null || lastValue == null) {
                //忽略
                continue;
            }
            double diff = 0D;
            if (value > lastValue) {
                diff = value - lastValue;
            }
            resultTable.put(key.getRedisConstant(), key.getValue(), diff);
        }
        return resultTable;
    }

    /**
     * 获取命令差值统计
     *
     * @param currentInfoMap
     * @param lastInfoMap
     * @return 命令统计
     */
    private Table<RedisConstant, String, Long> getCommandsDiff(Map<RedisConstant, Map<String, Object>> currentInfoMap,
                                                               Map<String, Object> lastInfoMap) {
        //没有上一次统计快照，忽略差值统计
        if (lastInfoMap == null || lastInfoMap.isEmpty()) {
            return HashBasedTable.create();
        }
        Map<String, Object> map = currentInfoMap.get(RedisConstant.Commandstats);
        Map<String, Long> currentMap = transferLongMap(map);
        Map<String, Object> lastObjectMap;
        if (lastInfoMap.get(RedisConstant.Commandstats.getValue()) == null) {
            lastObjectMap = new HashMap<String, Object>();
        } else {
            lastObjectMap = (Map<String, Object>) lastInfoMap.get(RedisConstant.Commandstats.getValue());
        }
        Map<String, Long> lastMap = transferLongMap(lastObjectMap);

        Table<RedisConstant, String, Long> resultTable = HashBasedTable.create();
        for (String command : currentMap.keySet()) {
            long lastCount = MapUtils.getLong(lastMap, command, 0L);
            long currentCount = MapUtils.getLong(currentMap, command, 0L);
            if (currentCount > lastCount) {
                resultTable.put(RedisConstant.Commandstats, command, currentCount - lastCount);
            }
        }
        return resultTable;
    }

    private AppStats getAppStats(long appId, long collectTime, Table<RedisConstant, String, Long> table,
                                 Map<RedisConstant, Map<String, Object>> infoMap) {
        AppStats appStats = new AppStats();
        appStats.setAppId(appId);
        appStats.setCollectTime(collectTime);
        appStats.setModifyTime(new Date());
        appStats.setUsedMemory(
                MapUtils.getLong(infoMap.get(RedisConstant.Memory), RedisInfoEnum.used_memory.getValue(), 0L));
        appStats.setUsedMemoryRss(
                MapUtils.getLong(infoMap.get(RedisConstant.Memory), RedisInfoEnum.used_memory_rss.getValue(), 0L));
        appStats.setHits(MapUtils.getLong(table.row(RedisConstant.Stats), RedisInfoEnum.keyspace_hits.getValue(), 0L));
        appStats.setMisses(
                MapUtils.getLong(table.row(RedisConstant.Stats), RedisInfoEnum.keyspace_misses.getValue(), 0L));
        appStats.setEvictedKeys(
                MapUtils.getLong(table.row(RedisConstant.Stats), RedisInfoEnum.evicted_keys.getValue(), 0L));
        appStats.setExpiredKeys(
                MapUtils.getLong(table.row(RedisConstant.Stats), RedisInfoEnum.expired_keys.getValue(), 0L));
        appStats.setNetInputByte(
                MapUtils.getLong(table.row(RedisConstant.Stats), RedisInfoEnum.total_net_input_bytes.getValue(), 0L));
        appStats.setNetOutputByte(
                MapUtils.getLong(table.row(RedisConstant.Stats), RedisInfoEnum.total_net_output_bytes.getValue(), 0L));

        appStats.setConnectedClients(MapUtils.getIntValue(infoMap.get(RedisConstant.Clients),
                RedisInfoEnum.connected_clients.getValue(), 0));
        appStats.setObjectSize(getObjectSize(infoMap));

        appStats.setCpuSys(MapUtils.getLongValue(table.row(RedisConstant.CPU), RedisInfoEnum.used_cpu_sys.getValue(),
                0));
        appStats.setCpuUser(
                MapUtils.getLongValue(table.row(RedisConstant.CPU), RedisInfoEnum.used_cpu_user.getValue(),
                        0));
        appStats.setCpuSysChildren(
                MapUtils.getLongValue(table.row(RedisConstant.CPU), RedisInfoEnum.used_cpu_sys_children.getValue(),
                        0));
        appStats.setCpuUserChildren(
                MapUtils.getLongValue(table.row(RedisConstant.CPU), RedisInfoEnum.used_cpu_user_children.getValue(),
                        0));
        logger.debug("appStats={} table={}", appStats, table);
        return appStats;
    }

    private long getObjectSize(Map<RedisConstant, Map<String, Object>> currentInfoMap) {
        Map<String, Object> sizeMap = currentInfoMap.get(RedisConstant.Keyspace);
        if (sizeMap == null || sizeMap.isEmpty()) {
            return 0L;
        }
        long result = 0L;
        Map<String, Long> longSizeMap = transferLongMap(sizeMap);

        for (Map.Entry<String, Long> entry : longSizeMap.entrySet()) {
            result += entry.getValue();
        }
        return result;
    }

    private Long getCommonCount(Map<?, ?> infoMap, RedisConstant redisConstant, String commond) {
        Object constantObject =
                infoMap.get(redisConstant) == null ? infoMap.get(redisConstant.getValue()) : infoMap.get(redisConstant);
        if (constantObject != null && (constantObject instanceof Map)) {
            Map constantMap = (Map) constantObject;
            if (constantMap.get(commond) == null) {
                return null;
            }
            return MapUtils.getLongValue(constantMap, commond);
        }
        return null;
    }

    private Double getDoubleCount(Map<?, ?> infoMap, RedisConstant redisConstant, String commond) {
        Object constantObject =
                infoMap.get(redisConstant) == null ? infoMap.get(redisConstant.getValue()) : infoMap.get(redisConstant);
        if (constantObject != null && (constantObject instanceof Map)) {
            Map constantMap = (Map) constantObject;
            return MapUtils.getDoubleValue(constantMap, commond);
        }
        return null;
    }

    /**
     * 转换redis 命令行统计结果
     *
     * @param commandMap
     * @return
     */
    private Map<String, Long> transferLongMap(Map<String, Object> commandMap) {
        Map<String, Long> resultMap = new HashMap<String, Long>();
        if (commandMap == null || commandMap.isEmpty()) {
            return resultMap;
        }
        for (Map.Entry<String, Object> entry : commandMap.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey();
            String value = entry.getValue().toString();
            String[] stats = value.split(",");
            if (stats.length == 0) {
                continue;
            }
            String[] calls = stats[0].split("=");
            if (calls == null || calls.length < 2) {
                continue;
            }
            long callCount = Long.parseLong(calls[1]);
            resultMap.put(key, callCount);
        }
        return resultMap;
    }

    private List<AppCommandStats> getCommandStatsList(long appId, long collectTime,
                                                      Table<RedisConstant, String, Long> table) {
        Map<String, Long> commandMap = table.row(RedisConstant.Commandstats);
        List<AppCommandStats> list = new ArrayList<AppCommandStats>();
        if (commandMap == null) {
            return list;
        }
        for (String key : commandMap.keySet()) {
            String commandName = key.replace("cmdstat_", "");
            long callCount = MapUtils.getLong(commandMap, key, 0L);
            if (callCount == 0L) {
                continue;
            }
            AppCommandStats commandStats = new AppCommandStats();
            commandStats.setAppId(appId);
            commandStats.setCollectTime(collectTime);
            commandStats.setCommandName(commandName);
            commandStats.setCommandCount(callCount);
            commandStats.setModifyTime(new Date());
            list.add(commandStats);
        }
        return list;
    }

    /**
     * 处理clusterinfo统计信息
     *
     * @param clusterInfo
     * @return
     */
    private Map<String, Object> processClusterInfoStats(String clusterInfo) {
        Map<String, Object> clusterInfoMap = new HashMap<String, Object>();
        String[] lines = clusterInfo.split("\r\n");
        for (String line : lines) {
            String[] pair = line.split(":");
            if (pair.length == 2) {
                clusterInfoMap.put(pair[0], pair[1]);
            }
        }
        return clusterInfoMap;
    }

    /**
     * 处理redis统计信息
     *
     * @param statResult 统计结果串
     */
    private Map<RedisConstant, Map<String, Object>> processRedisStats(String statResult) {
        Map<RedisConstant, Map<String, Object>> redisStatMap = new HashMap<RedisConstant, Map<String, Object>>();
        String[] data = statResult.split("\r\n");
        String key;
        int i = 0;
        int length = data.length;
        while (i < length) {
            if (data[i].contains("#")) {
                int index = data[i].indexOf('#');
                key = data[i].substring(index + 1);
                ++i;
                RedisConstant redisConstant = RedisConstant.value(key.trim());
                if (redisConstant == null) {
                    continue;
                }
                Map<String, Object> sectionMap = new LinkedHashMap<String, Object>();
                while (i < length && data[i].contains(":")) {
                    String[] pair = StringUtils.splitByWholeSeparator(data[i], ":");
                    sectionMap.put(pair[0], pair[1]);
                    i++;
                }
                redisStatMap.put(redisConstant, sectionMap);
            } else {
                i++;
            }
        }
        return redisStatMap;
    }

    /**
     * 根据infoMap的结果判断实例的主从
     *
     * @param infoMap
     * @return
     */
    private BooleanEnum hasSlaves(Map<RedisConstant, Map<String, Object>> infoMap) {
        Map<String, Object> replicationMap = infoMap.get(RedisConstant.Replication);
        if (MapUtils.isEmpty(replicationMap)) {
            return BooleanEnum.OTHER;
        }
        for (Entry<String, Object> entry : replicationMap.entrySet()) {
            String key = entry.getKey();
            //判断一个即可
            if (key != null && key.contains("slave0")) {
                return BooleanEnum.TRUE;
            }
        }
        return BooleanEnum.FALSE;
    }

    /**
     * 根据infoMap的结果判断实例的主从
     *
     * @param infoMap
     * @return
     */
    private BooleanEnum isMaster(Map<RedisConstant, Map<String, Object>> infoMap) {
        Map<String, Object> map = infoMap.get(RedisConstant.Replication);
        if (map == null || map.get(RedisInfoEnum.role.getValue()) == null) {
            //return null;
            return BooleanEnum.OTHER;
        }
        if ("master".equals(String.valueOf(map.get(RedisInfoEnum.role.getValue())))) {
            //return true;
            return BooleanEnum.TRUE;
        }
        //return false;
        return BooleanEnum.FALSE;
    }

    /**
     * 根据ip和port判断某一个实例当前是主还是从
     *
     * @param ip   ip
     * @param port port
     * @return 主返回true， 从返回false；
     */
    @Override
    public BooleanEnum isMaster(long appId, String ip, int port) {
        Jedis jedis = null;
        try {
            jedis = getJedis(appId, ip, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
            String info = jedis.info("all");
            Map<RedisConstant, Map<String, Object>> infoMap = processRedisStats(info);
            return isMaster(infoMap);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return BooleanEnum.OTHER;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 根据infoMap的结果判断实例的主从
     *
     * @param infoMap
     * @return
     */
    private BooleanEnum isSlaveAndPointedMasterUp(Map<RedisConstant, Map<String, Object>> infoMap, InstanceInfo masterInstance) {
        if (masterInstance == null) {
            return BooleanEnum.FALSE;
        }
        Map<String, Object> map = infoMap.get(RedisConstant.Replication);
        if (map == null || map.get(RedisInfoEnum.role.getValue()) == null) {
            //return null;
            return BooleanEnum.FALSE;
        }
        if ("slave".equals(String.valueOf(map.get(RedisInfoEnum.role.getValue())))
                && ("up".equals(String.valueOf(map.get(RedisInfoEnum.master_link_status.getValue()))))
                && (String.valueOf(map.get(RedisInfoEnum.master_host.getValue())).equals(masterInstance.getIp()))
                && (String.valueOf(map.get(RedisInfoEnum.master_port.getValue())).equals(String.valueOf(masterInstance.getPort())))
        ) {
            return BooleanEnum.TRUE;
        }
        return BooleanEnum.FALSE;
    }

    /**
     * 判断实例是否为从节点，并且与主节点连接有效
     *
     * @param appDesc
     * @param slaveInstance
     * @param masterInstance
     * @return
     */
    @Override
    public BooleanEnum isSlaveAndPointedMasterUp(AppDesc appDesc, InstanceInfo slaveInstance, InstanceInfo masterInstance) {
        Jedis jedis = null;
        try {
            jedis = getJedis(slaveInstance.getIp(), slaveInstance.getPort(), appDesc.getAppPassword());
            String info = jedis.info("all");
            Map<RedisConstant, Map<String, Object>> infoMap = processRedisStats(info);
            return isSlaveAndPointedMasterUp(infoMap, masterInstance);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return BooleanEnum.FALSE;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public long getDbSize(long appId, String ip, int port) {
        Jedis jedis = getJedis(appId, ip, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
        try {
            return jedis.dbSize();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return -1;
        } finally {
            jedis.close();
        }
    }

    /**
     * @Description: scan key
     * @Author: caoru
     * @CreateDate: 2018/11/13 16:08
     */
    @Override
    @Async
    public Future<List<String>> findInstancePatternKeys(long appId, String ip, int port, String pattern) {
        List<String> list = new ArrayList<String>();
        Jedis jedis = getJedis(appId, ip, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
        try {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(pattern).count(COUNT);
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                list.addAll(result.getResult());
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
            return new AsyncResult<List<String>>(list);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return new AsyncResult<List<String>>(list);
        } finally {
            jedis.close();
        }
    }

    /**
     * @Description: 查询单实例的big key
     * @Author: caoru
     * @CreateDate: 2018/11/13 16:08
     */
    @Override
    public List<String> findInstanceBigKey(long appId, String ip, int port, long startBytes, long endBytes) {
        List<String> list = new ArrayList<String>();
        Jedis jedis = getJedis(appId, ip, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
        try {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().count(COUNT);
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                for (String key : result.getResult()) {
                    String keyType = jedis.type(key);
                    if ("string".equals(keyType)) {
                        long len = jedis.strlen(key);
                        if (len > startBytes && len < endBytes) {
                            list.add(key);
                        }
                    } else {
                        String debugRes = jedis.debug(DebugParams.OBJECT(key));
                        long serializedlength = 0;
                        for (String param : Arrays.asList(debugRes.split(" "))) {
                            if (param.startsWith("serializedlength")) {
                                serializedlength = Long.parseLong(param.split(":")[1]);
                            }
                        }
                        if (serializedlength > startBytes && serializedlength < endBytes) {
                            list.add(key);
                        }
                    }
                }
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
            return list;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return list;
        } finally {
            jedis.close();
        }
    }

    /**
     * @Description: 查询应用的big key
     * @Author: caoru
     * @CreateDate: 2018/11/13 16:08
     */
    @Override
    public List<String> findClusterBigKey(long appId, long startBytes, long endBytes) {
        List<String> list = new ArrayList<String>();
        List<InstanceInfo> allMasterInstance = getAllHealthyInstanceInfo(appId);
        for (InstanceInfo masterInstance : allMasterInstance) {
            String ip = masterInstance.getIp();
            int port = masterInstance.getPort();
            List<String> res = findInstanceBigKey(appId, ip, port, startBytes, endBytes);
            list.addAll(res);
        }
        return list;
    }

    /**
     * @Description: 查询单实例的idle key
     * @Author: caoru
     * @CreateDate: 2018/11/13 16:08
     */
    @Override
    public List<String> findInstanceIdleKeys(long appId, String ip, int port, long idleDays) {
        List<String> list = new ArrayList<String>();
        Jedis jedis = getJedis(appId, ip, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
        try {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().count(COUNT);
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                for (String key : result.getResult()) {
                    String debugRes = jedis.debug(DebugParams.OBJECT(key));
                    long lruSecondsIdle = 0;
                    for (String param : Arrays.asList(debugRes.split(" "))) {
                        if (param.startsWith("lru_seconds_idle")) {
                            lruSecondsIdle = Long.parseLong(param.split(":")[1]);
                        }
                    }
                    if (lruSecondsIdle > idleDays * 3600 * 24) {
                        list.add(key);
                    }
                }
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
            return list;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return list;
        } finally {
            jedis.close();
        }
    }

    /**
     * @Description: 查询应用的idle key
     * @Author: caoru
     * @CreateDate: 2018/11/13 16:08
     */
    @Override
    public List<String> findClusterIdleKeys(long appId, long idleDays) {
        List<String> list = new ArrayList<String>();
        List<InstanceInfo> allMasterInstance = getAllHealthyInstanceInfo(appId);
        for (InstanceInfo masterInstance : allMasterInstance) {
            String ip = masterInstance.getIp();
            int port = masterInstance.getPort();
            List<String> res = findInstanceIdleKeys(appId, ip, port, idleDays);
            list.addAll(res);
        }
        return list;
    }

    /**
     * @Description: 查询单实例匹配的pattern
     * @Author: caoru
     * @CreateDate: 2018/11/13 16:08
     */
    @Override
    public void delInstancePatternKeys(long appId, String ip, int port, String pattern) {
        Jedis jedis = getJedis(appId, ip, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
        try {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(pattern).count(COUNT);
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                for (String key : result.getResult()) {
                    jedis.del(key);
                }
                cursor = result.getCursor();
            } while (!"0".equals(cursor));

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            jedis.close();
        }
    }

    /**
     * @Description: 查询应用的匹配的pattern
     * @Author: caoru
     * @CreateDate: 2018/11/13 16:08
     */
    @Override
    public void delClusterPatternKey(long appId, String pattern) {
        List<InstanceInfo> allMasterInstance = getAllHealthyInstanceInfo(appId);
        for (InstanceInfo masterInstance : allMasterInstance) {
            String ip = masterInstance.getIp();
            int port = masterInstance.getPort();
            delInstancePatternKeys(appId, ip, port, pattern);
        }
    }

    /**
     * 根据ip和port判断redis实例当前是否有从节点
     *
     * @param ip   ip
     * @param port port
     * @return 主返回true，从返回false；
     */
    @Override
    public BooleanEnum hasSlaves(long appId, String ip, int port) {
        Jedis jedis = getJedis(appId, ip, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
        try {
            String info = jedis.info("all");
            Map<RedisConstant, Map<String, Object>> infoMap = processRedisStats(info);
            return hasSlaves(infoMap);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        } finally {
            jedis.close();
        }
    }

    @Override
    public HostAndPort getMaster(String ip, int port, String password) {
        JedisPool jedisPool = maintainJedisPool(ip, port, password);
        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            String info = jedis.info(RedisConstant.Replication.getValue());
            Map<RedisConstant, Map<String, Object>> infoMap = processRedisStats(info);
            Map<String, Object> map = infoMap.get(RedisConstant.Replication);
            if (map == null) {
                return null;
            }
            String masterHost = MapUtils.getString(map, RedisInfoEnum.master_host.getValue(), null);
            int masterPort = MapUtils.getInteger(map, RedisInfoEnum.master_port.getValue(), 0);
            if (StringUtils.isNotBlank(masterHost) && masterPort > 0) {
                return new HostAndPort(masterHost, masterPort);
            }
            return null;
        } catch (Exception e) {
            logger.error("{}:{} getMaster failed {}", ip, port, e.getMessage(), e);
            return null;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public HostAndPort getSlave0(String ip, int port, String password) {
        JedisPool jedisPool = maintainJedisPool(ip, port, password);
        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            String info = jedis.info(RedisConstant.Replication.getValue());
            Map<RedisConstant, Map<String, Object>> infoMap = processRedisStats(info);
            Map<String, Object> map = infoMap.get(RedisConstant.Replication);
            if (map == null) {
                return null;
            }
            String slaveInfo = MapUtils.getString(map, "slave0");
            String slaveHost = "";
            int slavePort = 0;
            if (!StringUtil.isBlank(slaveInfo)) {
                for (String slave0 : slaveInfo.split(",")) {
                    if (slave0.indexOf("ip") > -1) {
                        slaveHost = slave0.replaceAll("ip=", "");
                    }
                    if (slave0.indexOf("port") > -1) {
                        slavePort = Integer.parseInt(slave0.replaceAll("port=", ""));
                    }
                }
            }
            if (StringUtils.isNotBlank(slaveHost) && slavePort > 0) {
                return new HostAndPort(slaveHost, slavePort);
            }
            return null;
        } catch (Exception e) {
            logger.error("{}:{} getMaster failed {}", ip, port, e.getMessage(), e);
            return null;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public boolean isRun(final String ip, final int port, final int retryTimes) {
        return isRun(ip, port, null, retryTimes);
    }

    public boolean isRun(final String ip, final int port, final String password, final int retryTimes) {
        boolean isRun = new IdempotentConfirmer(retryTimes) {
            private int timeOutFactor = 1;

            @Override
            public boolean execute() {
                Jedis jedis = null;
                try {
                    jedis = getJedis(ip, port, password);
                    jedis.getClient().setConnectionTimeout(Protocol.DEFAULT_TIMEOUT * (timeOutFactor++));
                    jedis.getClient().setSoTimeout(Protocol.DEFAULT_TIMEOUT * (timeOutFactor++));
                    String pong = jedis.ping();
                    return pong != null && "PONG".equalsIgnoreCase(pong);
                } catch (JedisDataException e) {
                    String message = e.getMessage();
                    logger.warn(e.getMessage());
                    if (StringUtils.isNotBlank(message) && message.startsWith("LOADING")) {
                        return true;
                    }
                    return false;
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
        return isRun;
    }

    @Override
    public boolean isRun(final String ip, final int port) {
        return isRun(ip, port, null);
    }

    @Override
    public boolean isSentinelRun(long appId, String ip, int port) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (appDesc.hasSentinelPwdFlag()) {
            return isRun(ip, port, appDesc.getAppPassword());
        } else {
            return isRun(ip, port);
        }
    }

    @Override
    public boolean isRun(final long appId, final String ip, final int port) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        return isRun(ip, port, appDesc.getAppPassword());
    }

    @Override
    public boolean isRun(final String ip, final int port, final String password) {
        return new IdempotentConfirmer() {
            private int timeOutFactor = 1;

            @Override
            public boolean execute() {
                try (Jedis jedis = getJedis(ip, port, password)) {
                    jedis.getClient().setConnectionTimeout(Protocol.DEFAULT_TIMEOUT);
                    jedis.getClient().setSoTimeout(Protocol.DEFAULT_TIMEOUT);
                    String pong = jedis.ping();
                    return "PONG".equalsIgnoreCase(pong);
                } catch (JedisDataException e) {
                    String message = e.getMessage();
                    logger.warn(e.getMessage());
                    return StringUtils.isNotBlank(message) && message.startsWith("LOADING");
                } catch (Exception e) {
                    logger.warn("{}:{} error count={} message is {} ", ip, port, timeOutFactor++, e.getMessage());
                    return false;
                }
            }
        }.run();
    }

    @Override
    public boolean shutdownSentinel(long appId, String host, int port) {
        boolean isRun = isSentinelRun(appId, host, port);
        if (!isRun) {
            return true;
        }
        final Jedis jedis = getSentinelJedis(appId, host, port);
        try {
            //关闭实例节点
            boolean isShutdown = new IdempotentConfirmer() {
                @Override
                public boolean execute() {
                    jedis.shutdown();
                    return true;
                }
            }.run();
            if (!isShutdown) {
                logger.error("{}:{} redis not shutdown!", host, port);
            }
            return isShutdown;
        } finally {
            jedis.close();
        }
    }

    @Override
    public boolean shutdown(long appId, String ip, int port) {
        boolean isRun = isRun(appId, ip, port);
        if (!isRun) {
            return true;
        }
        final Jedis jedis = getJedis(appId, ip, port);
        try {
            //关闭实例节点
            boolean isShutdown = new IdempotentConfirmer() {
                @Override
                public boolean execute() {
                    jedis.shutdown();
                    return true;
                }
            }.run();
            if (!isShutdown) {
                logger.error("{}:{} redis not shutdown!", ip, port);
            }
            return isShutdown;
        } finally {
            jedis.close();
        }
    }

    @Override
    public boolean forget(long appId, String ip, int port, String nodeId) {
        boolean isRun = isRun(appId, ip, port);     //todo: 除了isRun，是否还需要其他判断条件
        if (!isRun) {
            return true;
        }

        boolean isForget = new IdempotentConfirmer() {
            @Override
            public boolean execute() {
                String response = null;
                Jedis jedis = null;
                try {
                    jedis = getJedis(appId, ip, port);
                    response = jedis.clusterForget(nodeId);
                } catch (JedisDataException jde) {
                    //处于handshake状态的节点会抛异常：ERR Unknown node 92e90269c5f86a663a692c5bcf766ecdda80aa9e
                    logger.error(jde.getMessage(), jde);
                    response = "OK";
                } catch (Exception e) {
                    logger.error("appId {} instance {}:{}  forget instance {} error!", appId, ip, port, nodeId, e);
                } finally {
                    if (jedis != null) {
                        jedis.close();
                    }
                }
                return response != null && "OK".equalsIgnoreCase(response);
            }
        }.run();
        return isForget;
    }

    @Override
    public boolean shutdown(String ip, int port) {
        boolean isRun = isRun(ip, port);
        if (!isRun) {
            return true;
        }
        final Jedis jedis = getJedis(ip, port);
        try {
            //关闭实例节点
            boolean isShutdown = new IdempotentConfirmer() {
                @Override
                public boolean execute() {
                    jedis.shutdown();
                    return true;
                }
            }.run();
            if (!isShutdown) {
                logger.error("{}:{} redis not shutdown!", ip, port);
            }
            return isShutdown;
        } finally {
            jedis.close();
        }
    }

    @Override
    public boolean isHostSshCapable(String ip) {
        if (StringUtils.isBlank(ip)) {
            return false;
        }
        try {
            MachineInfo machine = machineDao.getMachineFullInfoByIp(ip.trim());
            return machine != null
                    && machine.getAvailable() == 1
                    && StringUtils.isNotBlank(machine.getSshUser())
                    && StringUtils.isNotBlank(machine.getSshPasswd());
        } catch (Exception e) {
            logger.warn("resolve machine ssh failed ip={}: {}", ip, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean requiresPlatformOnlyOffline(InstanceInfo instanceInfo) {
        if (instanceInfo == null) {
            return true;
        }
        if (instanceInfo.getHostId() == ExternalRedis.EXTERNAL_HOST_ID) {
            return true;
        }
        return !isHostSshCapable(instanceInfo.getIp());
    }

    @Override
    public boolean checkShutdownSuccess(InstanceInfo instanceInfo) {
        if (instanceInfo == null) {
            return false;
        }
        if (requiresPlatformOnlyOffline(instanceInfo)) {
            return checkShutdownSuccessByNetwork(instanceInfo);
        }
        //关闭节点后，判断配置文件句柄是否释放
        boolean executeFlag = false;
        int tryTimes = 3;
        long sleepTime = 2L;
        String host = instanceInfo.getIp();
        int port = instanceInfo.getPort();
        StringBuilder command = new StringBuilder();
        command.append("ps -ef | grep redis | grep redis-server | grep :").append(port).append("  | grep -v \"grep\"");
        while (tryTimes-- > 0) {
            try {
                String execute = SSHUtil.execute(host, command.toString());
                if (StringUtils.isEmpty(execute)) {
                    executeFlag = true;
                    break;
                }
                logger.info(String.format("check Instance shutdown not success, will one more time, appId:%s, instance:%s, command:%s", instanceInfo.getAppId(), instanceInfo.getHostPort(), command));
                TimeUnit.SECONDS.sleep(sleepTime);
            } catch (Exception e) {
                logger.error(String.format("check Instance shutdown error, appId:%s, instance:%s, command:%s, error: ", instanceInfo.getAppId(), instanceInfo.getHostPort(), command), e);
            }
        }
        return executeFlag;
    }

    private boolean checkShutdownSuccessByNetwork(InstanceInfo instanceInfo) {
        int tryTimes = 3;
        long sleepTime = 2L;
        long appId = instanceInfo.getAppId();
        String ip = instanceInfo.getIp();
        int port = instanceInfo.getPort();
        while (tryTimes-- > 0) {
            if (!isRun(appId, ip, port)) {
                return true;
            }
            logger.info("check Instance shutdown by network, appId:{} instance:{} retry left:{}", appId, instanceInfo.getHostPort(), tryTimes);
            try {
                TimeUnit.SECONDS.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !isRun(appId, ip, port);
    }

    @Override
    public String getClusterMyId(long appId, String ip, int port) {
        final Jedis jedis = getJedis(appId, ip, port);
        try {
            return jedis.clusterMyId();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return "";
        } finally {
            jedis.close();
        }
    }

    @Override
    public String getClusterNodes(long appId, String ip, int port) {
        final Jedis jedis = getJedis(appId, ip, port);
        try {
            return jedis.clusterNodes();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return "";
        } finally {
            jedis.close();
        }
    }

    /**
     * 返回当前实例的一些关键指标
     *
     * @param appId
     * @param ip
     * @param port
     * @param infoMap
     * @return
     */
    public InstanceStats getInstanceStats(long appId, String ip, int port,
                                          Map<RedisConstant, Map<String, Object>> infoMap) {
        if (infoMap == null) {
            return null;
        }
        // 查询最大内存限制
        Long maxMemory = this.getRedisMaxMemory(appId, ip, port);
        /**
         * 将实例的一些关键指标返回
         */
        InstanceStats instanceStats = new InstanceStats();
        instanceStats.setAppId(appId);
        InstanceInfo curInst = instanceDao.getMonitorInstByAppIdAndIpPort(appId, ip, port);
        if (curInst != null) {
            instanceStats.setHostId(curInst.getHostId());
            instanceStats.setInstId(curInst.getId());
        } else {
            logger.error("redis={}:{} not found", ip, port);
            return null;
        }
        instanceStats.setIp(ip);
        instanceStats.setPort(port);
        // CONFIG 成功则写入真实值（含主动设为 0）；失败则沿用库中旧值，避免冲掉
        long totalSystemMemory = MapUtils.getLongValue(infoMap.get(RedisConstant.Memory), "total_system_memory", 0L);
        if (maxMemory != null && maxMemory > 0) {
            instanceStats.setMaxMemory(maxMemory);
        } else if (totalSystemMemory > 0) {
            instanceStats.setMaxMemory(totalSystemMemory);
        } else {
            InstanceStats existing = instanceStatsDao.getInstanceStatsByHost(ip, port);
            if (existing != null && existing.getMaxMemory() > 0) {
                instanceStats.setMaxMemory(existing.getMaxMemory());
            } else if (maxMemory != null) {
                instanceStats.setMaxMemory(maxMemory);
            }
        }
        instanceStats.setUsedMemory(
                MapUtils.getLongValue(infoMap.get(RedisConstant.Memory), RedisInfoEnum.used_memory.getValue(), 0));
        instanceStats.setHits(
                MapUtils.getLongValue(infoMap.get(RedisConstant.Stats), RedisInfoEnum.keyspace_hits.getValue(), 0));
        instanceStats.setMisses(
                MapUtils.getLongValue(infoMap.get(RedisConstant.Stats), RedisInfoEnum.keyspace_misses.getValue(), 0));
        instanceStats.setCurrConnections(
                MapUtils.getIntValue(infoMap.get(RedisConstant.Clients), RedisInfoEnum.connected_clients.getValue(),
                        0));
        instanceStats.setCurrItems(getObjectSize(infoMap));
        instanceStats.setRole((byte) 1);
        if ("slave".equals(MapUtils.getString(infoMap.get(RedisConstant.Replication), RedisInfoEnum.role.getValue()))) {
            instanceStats.setRole((byte) 2);
        }
        instanceStats.setModifyTime(new Timestamp(System.currentTimeMillis()));
        instanceStats.setMemFragmentationRatio(MapUtils.getDoubleValue(infoMap.get(RedisConstant.Memory),
                RedisInfoEnum.mem_fragmentation_ratio.getValue(), 0.0));
        instanceStats.setAofDelayedFsync(
                MapUtils.getIntValue(infoMap.get(RedisConstant.Persistence), RedisInfoEnum.aof_delayed_fsync.getValue(),
                        0));
        instanceStats.setUptimeInSeconds(
                MapUtils.getLongValue(infoMap.get(RedisConstant.Server), "uptime_in_seconds", 0L));
        return instanceStats;
    }

    @Override
    public Long getRedisMaxMemory(final long appId, final String ip, final int port) {
        final String key = "maxmemory";
        final Map<String, Long> resultMap = new HashMap<String, Long>();
        boolean isSuccess = new IdempotentConfirmer() {
            private int timeOutFactor = 1;

            @Override
            public boolean execute() {
                Jedis jedis = null;
                try {
                    jedis = getJedis(appId, ip, port);
                    jedis.getClient().setConnectionTimeout(REDIS_DEFAULT_TIME * (timeOutFactor++));
                    jedis.getClient().setSoTimeout(REDIS_DEFAULT_TIME * (timeOutFactor++));
                    List<String> maxMemoryList = jedis.configGet(key); // 返回结果：list中是2个字符串，如："maxmemory",
                    // "4096000000"
                    if (maxMemoryList != null && maxMemoryList.size() >= 2) {
                        resultMap.put(key, Long.valueOf(maxMemoryList.get(1)));
                    }
                    return MapUtils.isNotEmpty(resultMap);
                } catch (Exception e) {
                    logger.warn("{}:{} errorMsg: {}", ip, port, e.getMessage());
                    return false;
                } finally {
                    if (jedis != null) {
                        jedis.close();
                    }
                }
            }
        }.run();
        if (isSuccess) {
            return MapUtils.getLong(resultMap, key);
        } else {
            logger.error("{}:{} getMaxMemory failed!", ip, port);
            return null;
        }
    }

    @Override
    public String executeCommand(AppDesc appDesc, String command, String userName) {

        if ("help".equalsIgnoreCase(command)) {
            return RedisReadOnlyCommandEnum.getAllCommand();
        }

        if (!RedisReadOnlyCommandEnum.contains(command)) {
            return "online app only support read-only and safe command";
        }
        int type = appDesc.getType();
        long appId = appDesc.getAppId();
        String password = appDesc.getAppPassword();
        if (type == ConstUtils.CACHE_REDIS_SENTINEL) {
            InstanceInfo masterInstance = findSentinelMasterInstance(appId);
            if (masterInstance != null) {
                try {
                    return executeCommand(appId, masterInstance.getIp(), masterInstance.getPort(), command);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    return "运行出错:" + e.getMessage();
                }
            }

            JedisSentinelPool jedisSentinelPool = getJedisSentinelPool(appDesc);
            if (jedisSentinelPool == null) {
                return "无法执行命令：未能定位 Master 节点，且 Sentinel 连接池初始化失败，请检查 Sentinel 地址、masterName 及网络连通性";
            }
            Jedis jedis = null;
            try {
                jedis = jedisSentinelPool.getResource();
                String host = jedis.getClient().getHost();
                int port = jedis.getClient().getPort();
                return executeCommand(appId, host, port, command);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return "运行出错:" + e.getMessage()
                        + "（无法从 Sentinel 连接池获取连接，请确认平台能访问 Sentinel/Redis 端口）";
            } finally {
                if (jedis != null) {
                    jedis.close();
                }
                jedisSentinelPool.destroy();
            }
        } else if (type == ConstUtils.CACHE_REDIS_STANDALONE) {
            List<InstanceInfo> instanceList = instanceDao.getInstListByAppId(appId);
            if (instanceList == null || instanceList.isEmpty()) {
                return "应用没有运行的实例";
            }

            InstanceInfo instanceInfo = null;
            for (InstanceInfo info : instanceList) {
                if (info.isOnline()) {
                    instanceInfo = info;
                    break;
                }
            }
            if (instanceInfo == null) {
                return "应用下没有运行状态的实例";
            }

            String host = instanceInfo.getIp();
            int port = instanceInfo.getPort();

            try {
                return executeCommand(appId, host, port, command);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return "运行出错:" + e.getMessage();
            }
        } else if (type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            List<InstanceInfo> instanceList = instanceDao.getInstListByAppId(appId);
            if (instanceList == null || instanceList.isEmpty()) {
                return "应用没有运行的实例";
            }
            Set<HostAndPort> clusterHosts = new LinkedHashSet<HostAndPort>();
            for (InstanceInfo instance : instanceList) {
                if (instance != null && instance.isOnline()) {
                    clusterHosts.add(new HostAndPort(instance.getIp(), instance.getPort()));
                }
            }
            if (clusterHosts.isEmpty()) {
                return "no run instance";
            }
            String commandKey = getCommandKey(command);
            if (StringUtils.isEmpty(commandKey)) {
                logger.error(String.format("executeCommand with empty commandKey, appDesc is : %s, command is: %s, user is : %s", appDesc.getAppId(), command, userName));
            }
            for (HostAndPort hostAndPort : clusterHosts) {
                HostAndPort rightHostAndPort = null;
                if (commandKey != null) {
                    rightHostAndPort = getClusterRightHostAndPort(hostAndPort.getHost(), hostAndPort.getPort(),
                            password, command, commandKey);
                } else {
                    rightHostAndPort = hostAndPort;
                }
                if (rightHostAndPort != null) {
                    try {
                        return executeCommand(appId, rightHostAndPort.getHost(), rightHostAndPort.getPort(), command);
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                        return "运行出错:" + e.getMessage();
                    }
                }
            }

        }
        return "不支持应用类型";
    }

    @Override
    public Object executeAdminCommand(AppDesc appDesc, ProtocolCommand command, String... args) {
        int type = appDesc.getType();
        long appId = appDesc.getAppId();
        String password = appDesc.getAppPassword();
        if (type == ConstUtils.CACHE_REDIS_SENTINEL) {
            List<InstanceInfo> instanceList = instanceDao.getInstListByAppId(appId);
            if (instanceList == null || instanceList.isEmpty()) {
                return "应用没有运行的实例";
            }
            String host = null;
            int port = 0;
            for (InstanceInfo instanceInfo : instanceList) {
                if (instanceInfo.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
                    continue;
                }
                host = instanceInfo.getIp();
                port = instanceInfo.getPort();
                BooleanEnum isMaster = this.isMaster(appId, host, port);
                if (isMaster.equals(BooleanEnum.TRUE)) {
                    break;
                } else {
                    host = null;
                    port = 0;
                }
            }
            Jedis jedis = null;
            try {
                jedis = this.getJedis(appId, host, port);
                return executeAdminRedisCommandByJedis(jedis, command, args);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return "运行出错:" + e.getMessage();
            } finally {
                if (jedis != null) {
                    jedis.close();
                }
            }
        } else if (type == ConstUtils.CACHE_REDIS_STANDALONE) {
            List<InstanceInfo> instanceList = instanceDao.getInstListByAppId(appId);
            if (instanceList == null || instanceList.isEmpty()) {
                return "应用没有运行的实例";
            }
            String host = null;
            int port = 0;
            for (InstanceInfo instanceInfo : instanceList) {
                host = instanceInfo.getIp();
                port = instanceInfo.getPort();
                BooleanEnum isMaster = this.isMaster(appId, host, port);
                if (isMaster.equals(BooleanEnum.TRUE)) {
                    break;
                } else {
                    host = null;
                    port = 0;
                }
            }
            Jedis jedis = null;
            try {
                jedis = this.getJedis(appId, host, port);
                return executeAdminRedisCommandByJedis(jedis, command, args);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return "运行出错:" + e.getMessage();
            } finally {
                if (jedis != null) {
                    jedis.close();
                }
            }
        } else if (type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            List<InstanceInfo> instanceList = instanceDao.getInstListByAppId(appId);
            if (instanceList == null || instanceList.isEmpty()) {
                return "应用没有运行的实例";
            }
            Set<HostAndPort> clusterHosts = new LinkedHashSet<HostAndPort>();
            for (InstanceInfo instance : instanceList) {
                if (instance != null && instance.isOnline()) {
                    clusterHosts.add(new HostAndPort(instance.getIp(), instance.getPort()));
                }
            }
            if (clusterHosts.isEmpty()) {
                return "no run instance";
            }
            String commandKey = null;
            if (args != null && args.length > 0) {
                commandKey = args[0];
            }
            HostAndPort rightHostAndPort = null;
            for (HostAndPort hostAndPort : clusterHosts) {
                if (commandKey != null) {
                    rightHostAndPort = getClusterRightHostAndPort(hostAndPort.getHost(), hostAndPort.getPort(),
                            password, command.toString(), commandKey);
                    if (rightHostAndPort != null) {
                        break;
                    }
                } else {
                    BooleanEnum isMaster = this.isMaster(appId, hostAndPort.getHost(), hostAndPort.getPort());
                    if (isMaster.equals(BooleanEnum.TRUE)) {
                        rightHostAndPort = hostAndPort;
                        break;
                    }
                }

            }
            if (rightHostAndPort != null) {
                Jedis jedis = null;
                try {
                    jedis = this.getJedis(appId, rightHostAndPort.getHost(), rightHostAndPort.getPort());
                    return executeAdminRedisCommandByJedis(jedis, command, args);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    return "运行出错:" + e.getMessage();
                } finally {
                    if (jedis != null) {
                        jedis.close();
                    }
                }
            }
        }
        return "不支持应用类型";
    }

    /**
     * 获取key对应的节点
     *
     * @param host
     * @param port
     * @param password
     * @param command
     * @param key
     * @return
     */
    private HostAndPort getClusterRightHostAndPort(String host, int port, String password, String command, String key) {
        Jedis jedis = null;
        try {
            jedis = getJedis(host, port, password);
            jedis.type(key);
            return new HostAndPort(host, port);
        } catch (JedisMovedDataException e) {
            return e.getTargetNode();
        } catch (JedisAskDataException e) {
            return e.getTargetNode();
        } catch (Exception e) {
            logger.error("command {} is error", command, e.getMessage(), e);
            return null;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    private String getCommandKey(String command) {
        String[] array = StringUtils.trim(command).split("\\s+");
        if (array.length > 1) {
            return array[1];
        } else {
            return null;
        }
    }

    @Override
    public String executeCommand(long appId, String host, int port, String command) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (appDesc == null) {
            return "not exist appId";
        }
        command = normalizeRedisCliCommand(command);

        if ("help".equalsIgnoreCase(command) || "--help".equalsIgnoreCase(command)) {
            return RedisReadOnlyCommandEnum.getAllCommand();
        }

        if (!RedisReadOnlyCommandEnum.contains(command)) {
            return "online app only support read-only and safe command ";
        }
        return executeWithDirectFallback(appDesc, appId, host, port, command, true, null);
    }


    private enum RedisCommandFailureKind {
        NONE, CONNECT, AUTH, COMMAND, UNKNOWN
    }

    private enum RedisCommandChannel {
        JEDIS, SSH
    }

    private static final class RedisCommandAttempt {
        private final boolean success;
        private final String output;
        private final RedisCommandFailureKind failureKind;
        private final String failureMessage;
        private final RedisCommandChannel channel;

        private RedisCommandAttempt(boolean success, String output, RedisCommandFailureKind failureKind,
                String failureMessage, RedisCommandChannel channel) {
            this.success = success;
            this.output = output;
            this.failureKind = failureKind;
            this.failureMessage = failureMessage;
            this.channel = channel;
        }

        static RedisCommandAttempt ok(RedisCommandChannel channel, String output) {
            return new RedisCommandAttempt(true, output != null ? output : "", RedisCommandFailureKind.NONE, null,
                    channel);
        }

        static RedisCommandAttempt fail(RedisCommandChannel channel, RedisCommandFailureKind kind, String message) {
            return new RedisCommandAttempt(false, null, kind, message, channel);
        }
    }

    private String executeWithDirectFallback(AppDesc appDesc, long appId, String host, int port,
                                             String command, boolean readOnlyShell, Integer timeout) {
        boolean external = isExternalInstance(appId, host, port);
        List<RedisCommandAttempt> attempts = new ArrayList<RedisCommandAttempt>();

        RedisCommandAttempt jedisAttempt = executeViaJedis(appId, host, port, command);
        attempts.add(jedisAttempt);
        if (jedisAttempt.success) {
            auditRedisCommandExec(appId, host, port, command, RedisCommandChannel.JEDIS, true, null);
            return jedisAttempt.output;
        }

        if (external) {
            auditRedisCommandExec(appId, host, port, command, RedisCommandChannel.JEDIS, false, "external-no-ssh");
            return buildExternalCommandError(host, port, attempts);
        }

        RedisCommandAttempt sshAttempt = executeViaRemoteShellAttempt(appDesc, host, port, command, readOnlyShell,
                timeout);
        attempts.add(sshAttempt);
        if (sshAttempt.success) {
            auditRedisCommandExec(appId, host, port, command, RedisCommandChannel.SSH, true, null);
            return sshAttempt.output;
        }

        auditRedisCommandExec(appId, host, port, command, RedisCommandChannel.SSH, false, "all-channels-failed");
        return buildManagedCommandError(host, port, attempts);
    }

    private RedisCommandAttempt executeViaJedis(long appId, String host, int port, String command) {
        Jedis jedis = null;
        try {
            InstanceInfo instanceInfo = instanceDao.getInstByIpAndPort(host, port);
            if (instanceInfo != null && TypeUtil.isRedisSentinel(instanceInfo.getType())) {
                jedis = getSentinelJedis(appId, host, port);
            } else {
                jedis = getJedis(appId, host, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
            }
            String trimmed = StringUtils.trim(command);
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 0) {
                return RedisCommandAttempt.fail(RedisCommandChannel.JEDIS, RedisCommandFailureKind.COMMAND,
                        "命令为空");
            }
            // --bigkeys/--scan 等并非 Redis 命令，服务端不认识，由客户端侧的 Java 实现承担
            if (redisNativeCommandService.isPseudoCommand(trimmed)) {
                return RedisCommandAttempt.ok(RedisCommandChannel.JEDIS,
                        redisNativeCommandService.executePseudo(jedis, trimmed));
            }
            String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];
            final String[] finalArgs = args;
            final String commandName = parts[0];
            // 集群实例命中其他分片时服务端返回 MOVED/ASK，这里跟随重定向，等价于 redis-cli -c
            Object result = redisNativeCommandService.executeFollowingRedirect(jedis,
                    (redirectHost, redirectPort) -> getJedis(appId, redirectHost, redirectPort,
                            REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME),
                    client -> {
                        try {
                            return client.sendCommand(
                                    Protocol.Command.valueOf(commandName.toUpperCase(Locale.ROOT)), finalArgs);
                        } catch (IllegalArgumentException unknownCommand) {
                            return client.sendCommand(buildRawCommand(commandName), finalArgs);
                        }
                    });
            return RedisCommandAttempt.ok(RedisCommandChannel.JEDIS, formatJedisCommandResult(result));
        } catch (Exception e) {
            RedisCommandFailureKind kind = classifyJedisFailure(e);
            String message = StringUtils.isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
            logger.warn("executeViaJedis failed {}:{} cmd={} kind={} msg={}", host, port, command, kind, message);
            return RedisCommandAttempt.fail(RedisCommandChannel.JEDIS, kind, message);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    private RedisCommandFailureKind classifyJedisFailure(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
        if (e instanceof JedisConnectionException
                || msg.contains("connection refused")
                || msg.contains("connect timed out")
                || msg.contains("connection timed out")
                || msg.contains("unable to connect")
                || msg.contains("unknown host")
                || msg.contains("no route to host")) {
            return RedisCommandFailureKind.CONNECT;
        }
        if (msg.contains("noauth") || msg.contains("wrongpass") || msg.contains("invalid password")) {
            return RedisCommandFailureKind.AUTH;
        }
        if (e instanceof JedisDataException || msg.startsWith("err ")) {
            return RedisCommandFailureKind.COMMAND;
        }
        return RedisCommandFailureKind.UNKNOWN;
    }

    private RedisCommandAttempt executeViaRemoteShellAttempt(AppDesc appDesc, String host, int port, String command,
            boolean readOnlyShell, Integer timeout) {
        String password = appDesc.getAppPassword();
        String shell = readOnlyShell
                ? RedisProtocol.getExecuteCommandShell(host, port, password, command)
                : RedisProtocol.getExecuteAdminCommandShell(host, port, password, command);
        logger.warn("executeRedisShell={}", shell);
        String result = timeout == null
                ? machineCenter.executeShell(host, shell)
                : machineCenter.executeShell(host, shell, timeout);
        if (StringUtils.isBlank(result)) {
            return RedisCommandAttempt.fail(RedisCommandChannel.SSH, RedisCommandFailureKind.CONNECT,
                    "SSH 无返回结果");
        }
        if (ConstUtils.INNER_ERROR.equals(result)) {
            return RedisCommandAttempt.fail(RedisCommandChannel.SSH, RedisCommandFailureKind.CONNECT,
                    "machine_info 未配置 SSH 或 SSH 执行失败");
        }
        if (result.contains("No such file or directory")) {
            return RedisCommandAttempt.fail(RedisCommandChannel.SSH, RedisCommandFailureKind.UNKNOWN,
                    "远程未安装 /home/redis/bin/redis-cli");
        }
        return RedisCommandAttempt.ok(RedisCommandChannel.SSH, result);
    }

    private void auditRedisCommandExec(long appId, String host, int port, String command,
            RedisCommandChannel channel, boolean ok, String detail) {
        logger.info("redisCommandExec appId={} channel={} {}:{} cmd={} ok={}{}",
                appId, channel, host, port, command, ok,
                StringUtils.isNotBlank(detail) ? " detail=" + detail : "");
    }

    private String buildExternalCommandError(String host, int port, List<RedisCommandAttempt> attempts) {
        StringBuilder sb = new StringBuilder();
        sb.append("命令执行失败（纳管实例不支持 SSH）：\n");
        appendAttemptLines(sb, attempts);
        sb.append("建议：确认 CacheCloud 服务器能网络直连 ").append(host).append(":").append(port);
        sb.append("，且应用密码正确");
        return sb.toString();
    }

    private String buildManagedCommandError(String host, int port, List<RedisCommandAttempt> attempts) {
        StringBuilder sb = new StringBuilder();
        sb.append("命令执行失败：\n");
        appendAttemptLines(sb, attempts);
        sb.append("建议：确认 CacheCloud 可访问 ").append(host).append(":").append(port);
        sb.append("；若为网络隔离环境，可在 machine_info 配置 SSH 作为备用通道");
        return sb.toString();
    }

    private void appendAttemptLines(StringBuilder sb, List<RedisCommandAttempt> attempts) {
        for (RedisCommandAttempt attempt : attempts) {
            if (attempt.success) {
                continue;
            }
            sb.append("• ").append(channelLabel(attempt.channel)).append("：");
            sb.append(StringUtils.isNotBlank(attempt.failureMessage) ? attempt.failureMessage
                    : attempt.failureKind.name()).append('\n');
        }
    }

    private String channelLabel(RedisCommandChannel channel) {
        switch (channel) {
            case JEDIS:
                return "Jedis";
            case SSH:
                return "SSH";
            default:
                return channel.name();
        }
    }

    private boolean isClusterApp(AppDesc appDesc) {
        return appDesc != null && appDesc.getType() == ConstUtils.CACHE_TYPE_REDIS_CLUSTER;
    }

    private ProtocolCommand buildRawCommand(String command) {
        final byte[] raw = SafeEncoder.encode(command);
        return () -> raw;
    }

    private String formatJedisCommandResult(Object result) {
        if (result == null) {
            return "";
        }
        if (result instanceof byte[]) {
            return SafeEncoder.encode((byte[]) result);
        }
        if (result instanceof List) {
            StringBuilder sb = new StringBuilder();
            appendJedisList((List<?>) result, sb);
            return sb.toString();
        }
        return String.valueOf(result);
    }

    private void appendJedisList(List<?> list, StringBuilder sb) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof byte[]) {
                sb.append(SafeEncoder.encode((byte[]) item));
            } else if (item instanceof List) {
                appendJedisList((List<?>) item, sb);
            } else if (item != null) {
                sb.append(item);
            }
            if (i < list.size() - 1) {
                sb.append('\n');
            }
        }
    }

    private boolean isExternalInstance(long appId, String host, int port) {
        InstanceInfo instanceInfo = instanceDao.getInstByIpAndPort(host, port);
        return instanceInfo != null && instanceInfo.getHostId() == ExternalRedis.EXTERNAL_HOST_ID;
    }

    @Override
    public String executeAdminCommand(long appId, String host, int port, String command, Integer timeout) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (appDesc == null) {
            return "not exist appId";
        }
        command = normalizeRedisCliCommand(command);
        if ("help".equalsIgnoreCase(command) || "--help".equalsIgnoreCase(command)) {
            return buildAdminCliHelp();
        }
        return executeWithDirectFallback(appDesc, appId, host, port, command, false, timeout);
    }

    private String buildAdminCliHelp() {
        String readOnly = RedisReadOnlyCommandEnum.getAllCommand().replace(",", ", ");
        return "CacheCloud redis-cli 使用说明:\n"
                + "1. 在提示符后直接输入 Redis 命令，不要带 redis-cli 前缀\n"
                + "2. 示例: info | info all | dbsize | cluster info | cluster nodes | get mykey\n\n"
                + "常用只读命令:\n" + readOnly + "\n\n"
                + "分析类命令（由平台以 Java 客户端实现，无需目标机安装 redis-cli）:\n"
                + String.join(", ", RedisNativeCommandService.supportedPseudoCommands()) + "\n\n"
                + "本工具为管理员工具，除只读命令外还可执行写操作，请谨慎使用。";
    }

    private String normalizeRedisCliCommand(String command) {
        if (StringUtils.isBlank(command)) {
            return command;
        }
        String trimmed = StringUtils.trim(command);
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("redis-cli ")) {
            return StringUtils.trim(trimmed.substring("redis-cli".length()));
        }
        return trimmed;
    }

    @Override
    public Object executeAdminRedisCommandByJedis(Jedis jedis, ProtocolCommand command, String... args) {
        Object o = jedis.sendCommand(command, args);
        return o;
    }

    /**
     * 哨兵应用：从已登记实例中定位当前 Master（直连，不依赖 Sentinel 连接池）。
     */
    private InstanceInfo findSentinelMasterInstance(long appId) {
        List<InstanceInfo> instanceList = instanceDao.getInstListByAppId(appId);
        if (instanceList == null || instanceList.isEmpty()) {
            return null;
        }
        for (InstanceInfo instanceInfo : instanceList) {
            if (instanceInfo.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
                continue;
            }
            if (!instanceInfo.isOnline()) {
                continue;
            }
            BooleanEnum masterFlag = isMaster(appId, instanceInfo.getIp(), instanceInfo.getPort());
            if (BooleanEnum.TRUE.equals(masterFlag)) {
                return instanceInfo;
            }
        }
        return null;
    }

    @Override
    public JedisSentinelPool getJedisSentinelPool(AppDesc appDesc) {
        if (appDesc == null) {
            logger.error("appDesc is null");
            return null;
        }
        if (appDesc.getType() != ConstUtils.CACHE_REDIS_SENTINEL) {
            logger.error("type={} is not sentinel", appDesc.getType());
            return null;
        }
        long appId = appDesc.getAppId();
        List<InstanceInfo> instanceInfos = instanceDao.getInstListByAppId(appId);
        instanceInfos = instanceInfos.stream().filter(instanceInfo -> instanceInfo.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus()).collect(Collectors.toList());

        String masterName = null;
        for (Iterator<InstanceInfo> i = instanceInfos.iterator(); i.hasNext(); ) {
            InstanceInfo instanceInfo = i.next();
            if (instanceInfo.getType() != ConstUtils.CACHE_REDIS_SENTINEL) {
                i.remove();
                continue;
            }
            if (masterName == null && StringUtils.isNotBlank(instanceInfo.getCmd())) {
                masterName = instanceInfo.getCmd();
            }
        }
        Set<String> sentinels = new HashSet<String>();
        for (InstanceInfo instanceInfo : instanceInfos) {
            sentinels.add(instanceInfo.getIp() + ":" + instanceInfo.getPort());
        }
        JedisSentinelPool jedisSentinelPool;
        if (appDesc.hasSentinelPwdFlag()) {
            jedisSentinelPool = new JedisSentinelPool(masterName, sentinels, appDesc.getAppPassword(), appDesc.getAppPassword());
        } else {
            jedisSentinelPool = new JedisSentinelPool(masterName, sentinels, appDesc.getAppPassword());
        }
        return jedisSentinelPool;
    }

    @Override
    public Map<String, String> getRedisConfigList(int instanceId) {
        if (instanceId <= 0) {
            return Collections.emptyMap();
        }
        InstanceInfo instanceInfo = instanceDao.getInstanceInfoById(instanceId);
        if (instanceInfo == null) {
            return Collections.emptyMap();
        }
        if (TypeUtil.isRedisType(instanceInfo.getType())) {
            Jedis jedis = null;
            try {
                jedis = getJedis(instanceInfo.getAppId(), instanceInfo.getIp(), instanceInfo.getPort(),
                        REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
                List<String> configs = jedis.configGet("*");
                Map<String, String> configMap = new LinkedHashMap<String, String>();
                for (int i = 0; i + 1 < configs.size(); i += 2) {
                    String key = configs.get(i);
                    String value = configs.get(i + 1);
                    configMap.put(key, StringUtils.defaultString(value));
                }
                return configMap;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            } finally {
                if (jedis != null) {
                    jedis.close();
                }
            }
        }

        return Collections.emptyMap();
    }

    @Override
    public List<RedisSlowLog> getRedisSlowLogs(int instanceId, int maxCount) {
        if (instanceId <= 0) {
            return Collections.emptyList();
        }
        InstanceInfo instanceInfo = instanceDao.getInstanceInfoById(instanceId);
        if (instanceInfo == null) {
            return Collections.emptyList();
        }
        if (TypeUtil.isRedisType(instanceInfo.getType())) {
            return getRedisSlowLogs(instanceInfo.getAppId(), instanceInfo.getIp(), instanceInfo.getPort(), maxCount);
        }
        return Collections.emptyList();
    }

    private List<RedisSlowLog> getRedisSlowLogs(long appId, String host, int port, int maxCount) {
        Jedis jedis = null;
        try {
            jedis = getJedis(appId, host, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
            List<RedisSlowLog> resultList = new ArrayList<RedisSlowLog>();
            List<Slowlog> slowlogs = null;
            if (maxCount > 0) {
                slowlogs = jedis.slowlogGet(maxCount);
            } else {
                slowlogs = jedis.slowlogGet();
            }
            if (slowlogs != null && slowlogs.size() > 0) {
                for (Slowlog sl : slowlogs) {
                    RedisSlowLog rs = new RedisSlowLog();
                    rs.setId(sl.getId());
                    rs.setExecutionTime(sl.getExecutionTime());
                    long time = sl.getTimeStamp() * 1000L;
                    rs.setDate(new Date(time));
                    rs.setTimeStamp(DateUtil.formatYYYYMMddHHMMSS(new Date(time)));
                    rs.setCommand(StringUtils.join(sl.getArgs(), " "));
                    HostAndPort client = sl.getClientIpPort();
                    if (client != null && StringUtils.isNotBlank(client.getHost())) {
                        String clientHost = client.getHost().trim();
                        if (!"?".equals(clientHost)) {
                            rs.setClientIp(clientHost);
                        }
                    }
                    if (StringUtils.isNotBlank(sl.getClientName())) {
                        rs.setClientName(sl.getClientName());
                    }
                    resultList.add(rs);
                }
            }
            return resultList;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public boolean configRewrite(final long appId, final String host, final int port) {
        return new IdempotentConfirmer() {
            @Override
            public boolean execute() {
                Jedis jedis = getJedis(appId, host, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
                try {
                    String response = jedis.configRewrite();
                    return response != null && "OK".equalsIgnoreCase(response);
                } finally {
                    jedis.close();
                }
            }
        }.run();
    }

    @Override
    public boolean cleanAppData(AppDesc appDesc, AppUser appUser) {
        if (appDesc == null) {
            return false;
        }

        long appId = appDesc.getAppId();

        // 线上应用不能清理数据
        if (AppDescEnum.AppTest.IS_TEST.getValue() != appDesc.getIsTest()) {
            logger.error("appId {} profile must be test", appId);
            return false;
        }

        // 必须是redis应用
        if (!TypeUtil.isRedisType(appDesc.getType())) {
            logger.error("appId {} type must be redis", appId);
            return false;
        }

        // 实例验证
        List<InstanceInfo> instanceList = instanceDao.getInstListByAppId(appId);
        if (CollectionUtils.isEmpty(instanceList)) {
            logger.error("appId {} instanceList is empty", appId);
            return false;
        }

        // 开始清除
        for (InstanceInfo instance : instanceList) {
            if (instance.getStatus() != InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                continue;
            }
            String host = instance.getIp();
            int port = instance.getPort();
            // master + 非sentinel节点
            BooleanEnum isMater = isMaster(appId, host, port);
            if (isMater == BooleanEnum.TRUE && !TypeUtil.isRedisSentinel(instance.getType())) {
                //异步线程处理
                AsyncThreadPoolFactory.DEFAULT_ASYNC_THREAD_POOL.execute(new Runnable() {
                    @Override
                    public void run() {
                        Jedis jedis = getJedis(appId, host, port);
                        jedis.getClient().setConnectionTimeout(REDIS_DEFAULT_TIME);
                        jedis.getClient().setSoTimeout(60000);
                        try {
                            logger.warn("{}:{} start clear data", host, port);
                            long start = System.currentTimeMillis();
                            String result = jedis.flushAll();
                            logger.warn("{}:{} finish clear data :{}, cost time:{} ms", host, port, result,
                                    (System.currentTimeMillis() - start));
                        } catch (Exception e) {
                            logger.error("clear redis: " + e.getMessage(), e);
                        } finally {
                            jedis.close();
                        }
                    }
                });
            }
        }

        //记录日志
        AppAuditLog appAuditLog = AppAuditLog.generate(appDesc, appUser, 0L, AppAuditLogTypeEnum.APP_CLEAN_DATA);
        appAuditLogDao.save(appAuditLog);

        return true;
    }

    @Override
    public boolean isSingleClusterNode(long appId, String host, int port) {
        final Jedis jedis = getJedis(appId, host, port);
        try {
            String clusterNodes = jedis.clusterNodes();
            if (StringUtils.isBlank(clusterNodes)) {
                throw new RuntimeException(host + ":" + port + "clusterNodes is null");
            }
            String[] nodeInfos = clusterNodes.split("\n");
            if (nodeInfos.length == 1) {
                return true;
            }
            return false;
        } finally {
            jedis.close();
        }
    }

    @Override
    public List<String> getClientList(int instanceId) {
        if (instanceId <= 0) {
            return Collections.emptyList();
        }
        ClientListCacheEntry cached = clientListCache.get(instanceId);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return new ArrayList<>(cached.data);
        }
        InstanceInfo instanceInfo = instanceDao.getInstanceInfoById(instanceId);
        if (instanceInfo == null) {
            return Collections.emptyList();
        }
        if (TypeUtil.isRedisType(instanceInfo.getType())) {
            Jedis jedis = null;
            try {
                jedis = getJedis(instanceInfo.getAppId(), instanceInfo.getIp(), instanceInfo.getPort(),
                        REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
                List<String> resultList = new ArrayList<String>();
                String clientList = jedis.clientList();
                if (StringUtils.isNotBlank(clientList)) {
                    String[] array = clientList.split("\n");
                    resultList.addAll(Arrays.asList(array));
                }
                clientListCache.put(instanceId, new ClientListCacheEntry(
                        System.currentTimeMillis() + CLIENT_LIST_CACHE_TTL_MS, new ArrayList<>(resultList)));
                return resultList;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            } finally {
                if (jedis != null) {
                    jedis.close();
                }
            }
        }
        return Collections.emptyList();
    }

    private static class ClientListCacheEntry {
        private final long expireAt;
        private final List<String> data;

        private ClientListCacheEntry(long expireAt, List<String> data) {
            this.expireAt = expireAt;
            this.data = data;
        }
    }

    @Override
    public List<Map<String, Object>> formatClientList(List<String> clientList) {
        return formatClientList(clientList, true);
    }

    @Override
    public List<Map<String, Object>> formatClientList(List<String> clientList, boolean includeConnectionDetails) {
        List<Map<String, Object>> clientMapList = clientList.stream().map(clientInfo -> parseClientInfo(clientInfo)).collect(Collectors.toList());
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        clientMapList.stream().forEach(clientMap -> {
            String addr = MapUtils.getString(clientMap, "addr");
            List<Map<String, Object>> list = result.get(addr);
            if (CollectionUtils.isEmpty(list)) {
                list = new ArrayList<>();
                result.put(addr, list);
            }
            list.add(clientMap);
        });
        return getClientInfoMap(result, includeConnectionDetails);
    }

    @Override
    public List<Map<String, Object>> getAppClientList(long appId, int condition) {
        List<InstanceInfo> instanceInfoList = appService.getAppOnlineInstanceInfo(appId);
        List<String> redisClientList = instanceInfoList.stream()
                .map(InstanceInfo::getIp)
                .distinct()
                .collect(Collectors.toList());

        Map<String, Map<Integer, Object>> finalResult = new HashMap<>();
        instanceInfoList.parallelStream().forEach(instanceInfo -> {
            int instanceId = instanceInfo.getId();
            List<Map<String, Object>> instanceClientList = formatClientList(getClientList(instanceId));
            synchronized (finalResult) {
                for (Map<String, Object> map : instanceClientList) {
                    String addr = MapUtils.getString(map, "addr");
                    finalResult.computeIfAbsent(addr, k -> new HashMap<>()).put(instanceId, map);
                }
            }
        });

        return formatAppClientList(finalResult, condition, redisClientList);
    }


    private List<Map<String, Object>> formatAppClientList(Map<String, Map<Integer, Object>> addrClientListMap,
                                                          int condition, List<String> redisClientList) {
        List<Map<String, Object>> finalResult = new ArrayList<>();
        List<String> ccWebClientList = webClientComponent.getWebClientIps();

        for (String addr : addrClientListMap.keySet()) {
            Map<Integer, Object> instanceClientListMap = addrClientListMap.get(addr);
            Map<String, Object> map = new HashMap<>();
            map.put("addr", addr);

            Set<String> flags = new HashSet<>();
            int size = 0;
            for (Integer instanceId : instanceClientListMap.keySet()) {
                Map<String, Object> clientInfo = (HashMap) instanceClientListMap.get(instanceId);
                Set<String> instanceFlags = (HashSet) clientInfo.get("clientTypeSet");
                flags.addAll(instanceFlags);
                int count = MapUtils.getIntValue(clientInfo, "count");
                size += count;
            }
            map.put("flags", flags);
            map.put("size", size);

            map.put("instanceClientStats", instanceClientListMap);

            switch (condition) {
                case 0:
                    if (!ccWebClientList.contains(addr) && !redisClientList.contains(addr)) {
                        finalResult.add(map);
                    }
                    break;
                case 1:
                    if (ccWebClientList.contains(addr)) {
                        finalResult.add(map);
                    }
                    break;
                case 2:
                    if (redisClientList.contains(addr)) {
                        finalResult.add(map);
                    }
                    break;
                case 3:
                    finalResult.add(map);
                    break;
            }
        }

        return finalResult;
    }

    private List<Map<String, Object>> getClientInfoMap(Map<String, List<Map<String, Object>>> map) {
        return getClientInfoMap(map, true);
    }

    private List<Map<String, Object>> getClientInfoMap(Map<String, List<Map<String, Object>>> map,
            boolean includeConnectionDetails) {
        List<Map<String, Object>> finalResult = new ArrayList<>();

        for (String addr : map.keySet()) {
            List<Map<String, Object>> clients = map.get(addr);
            Set<String> flagsSet = clients.stream().map(clientInfo ->
                            ClientTypeEnum.Method.getDesc(MapUtils.getString(clientInfo, "flags", "")))
                    .collect(Collectors.toSet());

            Map<String, Object> clientMap = new HashMap<>();
            clientMap.put("addr", addr);
            clientMap.put("clientTypeSet", flagsSet);
            clientMap.put("count", clients.size());
            if (includeConnectionDetails) {
                clientMap.put("clientInfoList", clients);
            }

            finalResult.add(clientMap);
        }

        return finalResult;
    }

    private Map<String, Object> parseClientInfo(String clientInfo) {
        Map<String, Object> clientInfoMap = new HashMap<>();
        String[] tmpArray1 = clientInfo.split(" ");
        if (tmpArray1 != null) {
            for (String tmp : tmpArray1) {
                String[] tmpArray2 = tmp.split("=|:");
                if (tmpArray2.length == 3) {
                    clientInfoMap.put(tmpArray2[0], tmpArray2[1]);
                    clientInfoMap.put("port", tmpArray2[2]);
                } else if (tmpArray2.length == 2) {
                    clientInfoMap.put(tmpArray2[0], tmpArray2[1]);
                }
            }
        }
        return clientInfoMap;
    }

    @Override
    public Map<String, String> getClusterLossSlots(long appId) {
        // 1.从应用中获取一个健康的主节点
        InstanceInfo sourceMasterInstance = getHealthyInstanceInfo(appId);
        if (sourceMasterInstance == null) {
            return Collections.emptyMap();
        }
        // 2. 获取所有slot和节点的对应关系
        Map<Integer, String> slotHostPortMap = getSlotsHostPortMap(appId, sourceMasterInstance.getIp(),
                sourceMasterInstance.getPort());
        // 3. 获取集群中失联的slot
        List<Integer> lossSlotList = getClusterLossSlots(appId, sourceMasterInstance.getIp(),
                sourceMasterInstance.getPort());
        // 3.1 将失联的slot列表组装成Map<String host:port,List<Integer> lossSlotList>
        Map<String, List<Integer>> hostPortSlotMap = new HashMap<String, List<Integer>>();
        if (CollectionUtils.isNotEmpty(lossSlotList)) {
            for (Integer lossSlot : lossSlotList) {
                String key = slotHostPortMap.get(lossSlot);
                if (hostPortSlotMap.containsKey(key)) {
                    hostPortSlotMap.get(key).add(lossSlot);
                } else {
                    List<Integer> list = new ArrayList<Integer>();
                    list.add(lossSlot);
                    hostPortSlotMap.put(key, list);
                }
            }
        }
        // 3.2 hostPortSlotMap组装成Map<String host:port,String startSlot-endSlot>
        Map<String, String> slotSegmentsMap = new HashMap<String, String>();
        for (Entry<String, List<Integer>> entry : hostPortSlotMap.entrySet()) {
            List<Integer> list = entry.getValue();
            List<String> slotSegments = new ArrayList<String>();
            int min = list.get(0);
            int max = min;
            for (int i = 1; i < list.size(); i++) {
                int temp = list.get(i);
                if (temp == max + 1) {
                    max = temp;
                } else {
                    slotSegments.add(String.valueOf(min) + "-" + String.valueOf(max));
                    min = temp;
                    max = temp;
                }
            }
            slotSegments.add(String.valueOf(min) + "-" + String.valueOf(max));
            slotSegmentsMap.put(entry.getKey(), slotSegments.toString());
        }
        return slotSegmentsMap;
    }

    /**
     * 从一个应用中获取一个健康的主节点
     *
     * @param appId
     * @return
     */
    @Override
    public InstanceInfo getHealthyInstanceInfo(long appId) {
        InstanceInfo sourceMasterInstance = null;
        List<InstanceInfo> appInstanceInfoList = instanceDao.getInstListByAppId(appId);
        if (CollectionUtils.isEmpty(appInstanceInfoList)) {
            logger.error("appId {} has not instances", appId);
            return null;
        }
        for (InstanceInfo instanceInfo : appInstanceInfoList) {
            int instanceType = instanceInfo.getType();
            if (!TypeUtil.isRedisCluster(instanceType)) {
                continue;
            }
            final String host = instanceInfo.getIp();
            final int port = instanceInfo.getPort();
            if (instanceInfo.getStatus() != InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                continue;
            }
            boolean isRun = isRun(appId, host, port);
            if (!isRun) {
                logger.warn("{}:{} is not run", host, port);
                continue;
            }
            BooleanEnum isMaster = isMaster(appId, host, port);
            if (isMaster != BooleanEnum.TRUE) {
                logger.debug("{}:{} is not master", host, port);
                continue;
            }
            sourceMasterInstance = instanceInfo;
            break;
        }
        return sourceMasterInstance;
    }

    /**
     * 从一个应用中获取所有健康master节点
     *
     * @param appId
     * @return 应用对应master节点列表
     */
    @Override
    public List<InstanceInfo> getAllHealthyInstanceInfo(long appId) {
        // return instances
        List<InstanceInfo> allInstance = new ArrayList<InstanceInfo>();
        List<InstanceInfo> appInstanceInfoList = instanceDao.getInstListByAppId(appId);
        if (CollectionUtils.isEmpty(appInstanceInfoList)) {
            logger.error("appId {} has not instances", appId);
            return null;
        }
        for (InstanceInfo instanceInfo : appInstanceInfoList) {
            int instanceType = instanceInfo.getType();
            if (!TypeUtil.isRedisCluster(instanceType)) {
                continue;
            }
            final String host = instanceInfo.getIp();
            final int port = instanceInfo.getPort();
            if (instanceInfo.getStatus() != InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                continue;
            }
            boolean isRun = isRun(appId, host, port);
            if (!isRun) {
                logger.warn("{}:{} is not run", host, port);
                continue;
            }
            BooleanEnum isMaster = isMaster(appId, host, port);
            if (isMaster != BooleanEnum.TRUE) {
                logger.debug("{}:{} is not master", host, port);
                continue;
            }
            // add exist redis
            allInstance.add(instanceInfo);
        }
        return allInstance;
    }

    @Override
    public List<InstanceLatencyHistory> collectRedisLatencyInfo(long appId, long collectTime, String host, int port) {
        Assert.isTrue(appId >= 0);
        Assert.hasText(host);
        Assert.isTrue(port > 0);
        // getInstByIpAndPort 只返回 status=1 的节点；存活探测以采集结果为准，
        // 心跳停止的节点必须继续采集，否则无法发现恢复
        InstanceInfo instanceInfo = instanceDao.getAllInstByIpAndPort(host, port);
        //不存在实例/已下线
        if (instanceInfo == null || !instanceInfo.isCollectable()) {
            return null;
        }
        if (TypeUtil.isRedisSentinel(instanceInfo.getType())) {
            //忽略sentinel redis实例
            return null;
        }

        // 从redis中获取延迟信息
        List<InstanceLatencyHistory> latencyHistoryList = getLatencyLatest(instanceInfo.getId(), appId, host, port);
        if (CollectionUtils.isEmpty(latencyHistoryList)) {
            return Collections.emptyList();
        }

        //入库
        String key = getThreadPoolKey() + "_" + host + "_" + port;
        boolean isOk = asyncService.submitFuture(getThreadPoolKey(), new KeyCallable<Boolean>(key) {
            @Override
            public Boolean execute() {
                try {
                    instanceLatencyHistoryDao.batchSave(latencyHistoryList);
                    return true;
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    return false;
                }
            }
        });
        if (!isOk) {
            logger.error("latencyHistory submitFuture failed,appId:{},collectTime:{},host:{},port:{}", appId, collectTime,
                    host, port);
        }
        return latencyHistoryList;
    }

    /**
     * 取该实例每个延迟事件已入库的最新时间戳（毫秒），取不到时返回空表。
     *
     * <p>查不出来就当作没有水位线全量入库，唯一索引 (instance_id,event,execute_date)
     * 兜住重复行，宁可多写一轮也不能因为一次查询失败丢掉真实样本。</p>
     */
    private Map<String, Long> getLastLatencyTimeByEvent(long instanceId) {
        Map<String, Long> result = new HashMap<String, Long>();
        try {
            List<Map<String, Object>> rows = instanceLatencyHistoryDao.getMaxExecuteDateGroupByEvent(instanceId);
            if (CollectionUtils.isEmpty(rows)) {
                return result;
            }
            for (Map<String, Object> row : rows) {
                Object event = row.get("event");
                Object maxDate = row.get("max_execute_date");
                if (event == null || !(maxDate instanceof Date)) {
                    continue;
                }
                result.put(String.valueOf(event), ((Date) maxDate).getTime());
            }
        } catch (Exception e) {
            logger.warn("query last latency time failed, instanceId={}: {}", instanceId, e.getMessage());
        }
        return result;
    }

    private List<InstanceLatencyHistory> getLatencyLatest(long instanceId, long appId, String host, int port) {
        Jedis jedis = null;
        try {
            jedis = getJedis(appId, host, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);

            List<InstanceLatencyHistory> resultList = new ArrayList<>();
            List<LatencyItem> latencyItems = JedisUtil.latencyLatest(jedis);
            List<Object> subResultList = null;
            if (CollectionUtils.isNotEmpty(latencyItems)) {
                List<String> eventList = latencyItems.stream().map(latencyItem -> latencyItem.getEvent()).collect(Collectors.toList());

                // 原来每读完一个事件就 LATENCY RESET 掉，靠清空服务端缓冲来保证下一轮不重复。
                // 代价是实例自身的延迟历史被平台独占：运维手工执行 LATENCY HISTORY / LATENCY DOCTOR
                // 永远看不到数据。改为只读不写，重复由采集侧按「已入库最新时间」自行过滤。
                Pipeline pipeline = jedis.pipelined();
                for (String event : eventList) {
                    PipelineUtil.latencyHistory(pipeline, event);
                }
                subResultList = pipeline.syncAndReturnAll();

                if (CollectionUtils.isNotEmpty(subResultList)) {
                    Map<String, Long> lastStoredMillis = getLastLatencyTimeByEvent(instanceId);
                    for (int i = 0; i < subResultList.size(); i++) {
                        Object o = subResultList.get(i);
                        if (o instanceof List) {
                            String event = eventList.get(i);
                            // Redis 的延迟监控对同一秒只保留一条样本（同秒再次触发是就地取最大值），
                            // 所以「时间戳严格大于已入库最新值」就是完备的去重条件，不会漏样本。
                            long watermark = lastStoredMillis.containsKey(event) ? lastStoredMillis.get(event) : Long.MIN_VALUE;
                            List<Object> latencyHistoryItems = (List<Object>) o;
                            List<InstanceLatencyHistory> instanceLatencyHistoryList = latencyHistoryItems.stream()
                                    .map(data -> {
                                        List<Object> properties = (List<Object>) data;
                                        LatencyHistoryItem latencyHistory = new LatencyHistoryItem(properties);
                                        return new InstanceLatencyHistory(
                                                instanceId, appId, host, port, event,
                                                new Date(latencyHistory.getTimeStamp() * 1000L),
                                                latencyHistory.getExecutionTime());
                                    })
                                    .filter(history -> history.getExecuteDate().getTime() > watermark)
                                    .collect(Collectors.toList());
                            resultList.addAll(instanceLatencyHistoryList);
                        }
                    }
                }
            }
            return resultList;
        } catch (Exception e) {
            logger.error(String.format("appId:%s, host:%s,port:%s,error:%s", appId, host, port, e.getMessage()), e);
            return Collections.emptyList();
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * clusterslots命令拼接成Map<Integer slot, String host:port>
     *
     * @param host
     * @param port
     * @return
     */
    @Override
    public Map<Integer, String> getSlotHostPortMap(long appId, String host, int port) {
        return getSlotsHostPortMap(appId, host, port);
    }

    private Map<Integer, String> getSlotsHostPortMap(long appId, String host, int port) {
        Map<Integer, String> slotHostPortMap = new HashMap<Integer, String>();
        Jedis jedis = null;
        try {
            jedis = getJedis(appId, host, port);
            List<Object> slots = jedis.clusterSlots();
            for (Object slotInfoObj : slots) {
                List<Object> slotInfo = (List<Object>) slotInfoObj;
                if (slotInfo.size() <= 2) {
                    continue;
                }
                List<Integer> slotNums = getAssignedSlotArray(slotInfo);

                // hostInfos
                List<Object> hostInfos = (List<Object>) slotInfo.get(2);
                if (hostInfos.size() <= 0) {
                    continue;
                }
                HostAndPort targetNode = generateHostAndPort(hostInfos);

                for (Integer slot : slotNums) {
                    slotHostPortMap.put(slot, targetNode.getHost() + ":" + targetNode.getPort());
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        return slotHostPortMap;
    }

    private HostAndPort generateHostAndPort(List<Object> hostInfos) {
        return new HostAndPort(SafeEncoder.encode((byte[]) hostInfos.get(0)),
                ((Long) hostInfos.get(1)).intValue());
    }

    private List<Integer> getAssignedSlotArray(List<Object> slotInfo) {
        List<Integer> slotNums = new ArrayList<Integer>();
        for (int slot = ((Long) slotInfo.get(0)).intValue(); slot <= ((Long) slotInfo.get(1))
                .intValue(); slot++) {
            slotNums.add(slot);
        }
        return slotNums;
    }

    @Override
    public List<Integer> getClusterLossSlots(long appId, String host, int port) {
        InstanceInfo instanceInfo = instanceDao.getAllInstByIpAndPort(host, port);
        if (instanceInfo == null) {
            logger.warn("{}:{} instanceInfo is null", host, port);
            return Collections.emptyList();
        }
        if (!TypeUtil.isRedisCluster(instanceInfo.getType())) {
            logger.warn("{}:{} is not rediscluster type", host, port);
            return Collections.emptyList();
        }
        List<Integer> clusterLossSlots = new ArrayList<Integer>();
        Jedis jedis = null;
        try {
            jedis = getJedis(appId, host, port, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
            String clusterNodes = jedis.clusterNodes();
            if (StringUtils.isBlank(clusterNodes)) {
                throw new RuntimeException(host + ":" + port + "clusterNodes is null");
            }
            Set<Integer> allSlots = new LinkedHashSet<Integer>();
            for (int i = 0; i <= 16383; i++) {
                allSlots.add(i);
            }

            // 解析
            ClusterNodeInformationParser nodeInfoParser = new ClusterNodeInformationParser();
            for (String nodeInfo : clusterNodes.split("\n")) {
                if (StringUtils.isNotBlank(nodeInfo) && !nodeInfo.contains("disconnected") && !nodeInfo
                        .contains("fail")) {
                    if (nodeInfo.contains("@")) {
                        // redis4.0 兼容集群协议 6397@16397
                        nodeInfo = nodeInfo.replaceAll(nodeInfo.substring(nodeInfo.indexOf("@"),
                                nodeInfo.indexOf("@") + nodeInfo.split("@")[1].indexOf(" ") + 1), "");
                    }
                    ClusterNodeInformation clusterNodeInfo = nodeInfoParser
                            .parse(nodeInfo, new HostAndPort(host, port));
                    List<Integer> availableSlots = clusterNodeInfo.getAvailableSlots();
                    for (Integer slot : availableSlots) {
                        allSlots.remove(slot);
                    }
                }
            }
            clusterLossSlots = new ArrayList<Integer>(allSlots);
        } catch (Exception e) {
            logger.error("getClusterLossSlots: " + e.getMessage(), e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        return clusterLossSlots;
    }

    @Override
    public List<Integer> getInstanceSlots(long appId, String healthHost, int healthPort, String lossSlotsHost,
                                          int lossSlotsPort) {
        InstanceInfo instanceInfo = instanceDao.getAllInstByIpAndPort(healthHost, healthPort);
        if (instanceInfo == null) {
            logger.warn("{}:{} instanceInfo is null", healthHost, healthPort);
            return Collections.emptyList();
        }
        if (!TypeUtil.isRedisCluster(instanceInfo.getType())) {
            logger.warn("{}:{} is not rediscluster type", healthHost, healthPort);
            return Collections.emptyList();
        }
        List<Integer> clusterLossSlots = new ArrayList<Integer>();
        Jedis jedis = null;
        try {
            jedis = getJedis(appId, healthHost, healthPort, REDIS_DEFAULT_TIME, REDIS_DEFAULT_TIME);
            String clusterNodes = jedis.clusterNodes();
            if (StringUtils.isBlank(clusterNodes)) {
                throw new RuntimeException(healthHost + ":" + healthPort + "clusterNodes is null");
            }
            // 解析获取丢失slots
            ClusterNodeInformationParser nodeInfoParser = new ClusterNodeInformationParser();
            for (String nodeInfo : clusterNodes.split("\n")) {
                if (StringUtils.isNotBlank(nodeInfo) && nodeInfo.contains("fail") && nodeInfo
                        .contains(lossSlotsHost + ":" + lossSlotsPort)) {
                    if (nodeInfo.contains("@")) {
                        // redis4.0 兼容集群协议 6397@16397
                        nodeInfo = nodeInfo.replaceAll(nodeInfo.substring(nodeInfo.indexOf("@"),
                                nodeInfo.indexOf("@") + nodeInfo.split("@")[1].indexOf(" ") + 1), "");
                    }
                    ClusterNodeInformation clusterNodeInfo = nodeInfoParser
                            .parse(nodeInfo, new HostAndPort(healthHost, healthPort));
                    clusterLossSlots = clusterNodeInfo.getAvailableSlots();
                }
            }
        } catch (Exception e) {
            logger.error("getClusterLossSlots: " + e.getMessage(), e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        return clusterLossSlots;
    }

    @PreDestroy
    public void destory() {
        for (JedisPool jedisPool : jedisPoolMap.values()) {
            jedisPool.destroy();
        }
    }

    @Override
    public List<InstanceSlowLog> getInstanceSlowLogByAppId(long appId) {
        try {
            return instanceSlowLogDao.getByAppId(appId);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<InstanceSlowLog> getInstanceSlowLogByAppId(long appId, Date startDate, Date endDate) {
        try {
            return instanceSlowLogDao.search(appId, startDate, endDate);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, Long> getInstanceSlowLogCountMapByAppId(Long appId, Date startDate, Date endDate) {
        try {
            List<Map<String, Object>> list = instanceSlowLogDao
                    .getInstanceSlowLogCountMapByAppId(appId, startDate, endDate);
            if (CollectionUtils.isEmpty(list)) {
                return Collections.emptyMap();
            }
            Map<String, Long> resultMap = new LinkedHashMap<String, Long>();
            for (Map<String, Object> map : list) {
                long count = MapUtils.getLongValue(map, "count");
                String hostPort = MapUtils.getString(map, "hostPort");
                if (StringUtils.isNotBlank(hostPort)) {
                    resultMap.put(hostPort, count);
                }
            }
            return resultMap;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    @Override
    public Map<String, InstanceSlotModel> getClusterSlotsMap(long appId) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (!TypeUtil.isRedisCluster(appDesc.getType())) {
            return Collections.emptyMap();
        }
        // 最终结果
        Map<String, InstanceSlotModel> resultMap = new HashMap<String, InstanceSlotModel>();

        // 找到一个运行的节点用来执行cluster slots
        List<InstanceInfo> instanceList = instanceDao.getInstListByAppId(appId);
        String host = null;
        int port = 0;
        for (InstanceInfo instanceInfo : instanceList) {
            // 下线和心跳停止 均跳过
            if (instanceInfo.isOffline() || instanceInfo.getStatus() == InstanceStatusEnum.ERROR_STATUS.getStatus()) {
                continue;
            }
            host = instanceInfo.getIp();
            port = instanceInfo.getPort();
            boolean isRun = isRun(appId, host, port);
            if (isRun) {
                break;
            }
        }
        if (StringUtils.isBlank(host) || port <= 0) {
            return Collections.emptyMap();
        }

        // 获取cluster slots
        List<Object> clusterSlotList = null;
        Jedis jedis = null;
        try {
            jedis = getJedis(appId, host, port);
            clusterSlotList = jedis.clusterSlots();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        if (clusterSlotList == null || clusterSlotList.size() == 0) {
            return Collections.emptyMap();
        }
        //clusterSlotList形如：
        //		[0, 1, [[B@5caf905d, 6380], [[B@27716f4, 6379]]
        //		[3, 4096, [[B@8efb846, 6380], [[B@2a84aee7, 6379]]
        //		[12291, 16383, [[B@a09ee92, 6383], [[B@30f39991, 6382]]
        //		[2, 2, [[B@452b3a41, 6381], [[B@4a574795, 6382]]
        //		[8194, 12290, [[B@f6f4d33, 6381], [[B@23fc625e, 6382]]
        //		[4097, 8193, [[B@3f99bd52, 6380], [[B@4f023edb, 6381]]

        for (Object clusterSlotObj : clusterSlotList) {
            List<Object> slotInfoList = (List<Object>) clusterSlotObj;
            if (slotInfoList.size() <= 2) {
                continue;
            }
            //获取slot的start到end相关
            int startSlot = ((Long) slotInfoList.get(0)).intValue();
            int endSlot = ((Long) slotInfoList.get(1)).intValue();
            String slotDistribute = getStartToEndSlotDistribute(startSlot, endSlot);
            List<Integer> slotList = getStartToEndSlotList(startSlot, endSlot);

            List<Object> masterInfoList = (List<Object>) slotInfoList.get(2);
            String tempHost = SafeEncoder.encode((byte[]) masterInfoList.get(0));
            int tempPort = ((Long) masterInfoList.get(1)).intValue();
            String hostPort = tempHost + ":" + tempPort;
            if (resultMap.containsKey(hostPort)) {
                InstanceSlotModel instanceSlotModel = resultMap.get(hostPort);
                instanceSlotModel.getSlotDistributeList().add(slotDistribute);
                instanceSlotModel.getSlotList().addAll(slotList);
            } else {
                InstanceSlotModel instanceSlotModel = new InstanceSlotModel();
                instanceSlotModel.setHost(tempHost);
                instanceSlotModel.setPort(tempPort);
                List<String> slotDistributeList = new ArrayList<String>();
                slotDistributeList.add(slotDistribute);
                instanceSlotModel.setSlotDistributeList(slotDistributeList);
                instanceSlotModel.setSlotList(slotList);
                resultMap.put(hostPort, instanceSlotModel);
            }
        }
        return resultMap;
    }

    /**
     * 获取slot列表
     *
     * @param startSlot
     * @param endSlot
     * @return
     */
    private List<Integer> getStartToEndSlotList(int startSlot, int endSlot) {
        List<Integer> slotList = new ArrayList<Integer>();
        if (startSlot == endSlot) {
            slotList.add(startSlot);
        } else {
            for (int i = startSlot; i <= endSlot; i++) {
                slotList.add(i);
            }
        }
        return slotList;
    }

    /**
     * 0,4096 0-4096
     * 2,2 2-2
     *
     * @return
     */
    private String getStartToEndSlotDistribute(int startSlot, int endSlot) {
        if (startSlot == endSlot) {
            return String.valueOf(startSlot);
        } else {
            return startSlot + "-" + endSlot;
        }
    }

    @Override
    public String getRedisVersion(long appId, String ip, int port) {
        Map<RedisConstant, Map<String, Object>> infoAllMap = getInfoStats(appId, ip, port);
        if (MapUtils.isEmpty(infoAllMap)) {
            return null;
        }
        Map<String, Object> serverMap = infoAllMap.get(RedisConstant.Server);
        if (MapUtils.isEmpty(serverMap)) {
            return null;
        }
        return MapUtils.getString(serverMap, "redis_version");
    }

    @Override
    public Boolean getRedisReplicationStatus(long appId, String ip, int port) {

        Map<RedisConstant, Map<String, Object>> infoAllMap = getInfoStats(appId, ip, port);
        if (MapUtils.isEmpty(infoAllMap)) {
            return false;
        }
        Map<String, Object> serverMap = infoAllMap.get(RedisConstant.Replication);
        if (MapUtils.isEmpty(serverMap)) {
            return false;
        }
        /**
         * 主从failover (info replication) slave0 state 状态变化: wait_bgsave -> send_bulk -> online
         * 1.slave0	ip=${ip},port=${port},state=online,offset=413125529634,lag=1
         * 2.master_repl_offset	413125537241
         */
        String slave0 = MapUtils.getString(serverMap, "slave0");
        String master_repl_offset = MapUtils.getString(serverMap, "master_repl_offset");
        String role = MapUtils.getString(serverMap, "role");

        logger.info("salve0 :{} ,master_repl_offset :{}", slave0, master_repl_offset);
        try {
            if (!StringUtils.isEmpty(slave0) && slave0.contains("state=online") && "master".equals(role)
                    && !StringUtils.isEmpty(master_repl_offset)) {

                long slave_offset = 0L;
                for (String info : slave0.split(",")) {
                    if (info.contains("offset")) {
                        logger.info(" slave offset = {} ", info.replaceAll("offset=", ""));
                        slave_offset = Long.parseLong(info.replaceAll("offset=", ""));
                    }
                }
                // 从偏移量差值 ,load内存数据 offset
                if (slave_offset == 0) {
                    return false;
                }
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return false;
        }
    }

    @Override
    public String getNodeId(long appId, String ip, int port) {
        final Jedis jedis = getJedis(appId, ip, port);
        try {
            final StringBuilder clusterNodes = new StringBuilder();
            boolean isGetNodes = new IdempotentConfirmer() {
                @Override
                public boolean execute() {
                    String nodes = jedis.clusterNodes();
                    if (nodes != null && nodes.length() > 0) {
                        clusterNodes.append(nodes);
                        return true;
                    }
                    return false;
                }
            }.run();
            if (!isGetNodes) {
                logger.error("{}:{} clusterNodes failed", jedis.getClient().getHost(), jedis.getClient().getPort());
                return null;
            }
            for (String infoLine : clusterNodes.toString().split("\n")) {
                if (infoLine.contains("myself")) {
                    String nodeId = infoLine.split(" ")[0];
                    return nodeId;
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        return null;
    }

    @Override
    public Jedis getJedis(long appId, String host, int port) {
        return getJedis(appId, host, port, Protocol.DEFAULT_TIMEOUT, Protocol.DEFAULT_TIMEOUT);
    }

    @Override
    public Jedis getJedis(long appId, String host, int port, int connectionTimeout, int soTimeout) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        String password = appDesc.getAppPassword();
        Jedis jedis = getJedis(host, port, connectionTimeout, soTimeout, password);
        return jedis;
    }

    @Override
    public Jedis getSentinelJedis(long appId, String host, int port) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        ExternalRedis externalRedis = externalRedisDao.getByAppId(appId);
        if (externalRedis != null) {
            return getJedis(host, port, externalRedis.getSentinelPassword());
        }
        if (appDesc.hasSentinelPwdFlag()) {
            return getJedis(host, port, appDesc.getAppPassword());
        }
        return getJedis(host, port);
    }

    @Override
    public Jedis getJedis(String host, int port, String authPassword) {
        return getJedis(host, port, Protocol.DEFAULT_TIMEOUT, Protocol.DEFAULT_TIMEOUT, authPassword);
    }

    @Override
    public Jedis getJedis(String host, int port) {
        return getJedis(host, port, null);
    }

    @Override
    public Jedis getJedis(String host, int port, String password, int connectionTimeout, int soTimeout) {
        return getJedis(host, port, connectionTimeout, soTimeout, password);
    }

    private Jedis getJedis(String host, int port, int connectionTimeout, int soTimeout, String authPassword) {
        String connectionHost = RedisHostResolver.resolve(managedLoopbackHost, host);
        Jedis jedis = new Jedis(connectionHost, port);
        jedis.getClient().setConnectionTimeout(connectionTimeout);
        jedis.getClient().setSoTimeout(soTimeout);
        try {
            if (StringUtils.isBlank(authPassword)) {
                // 保证存活性
                jedis.ping();
            } else {
                AuthUtil.auth(jedis, authPassword);
            }
        } catch (RuntimeException e) {
            jedis.close();
            throw e;
        }
        return jedis;
    }

    private void fixReadOnlyOfCluster(long appId, Jedis jedis) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (appDesc == null) {
            return;
        }
    }

    @Override
    public boolean sendDeployRedisRelateCollectionMsg(long appId, String host, int port) {
        //todo
        return true;
    }

    @Override
    public boolean checkNutCrackerConfIsSame(long appId) {
        List<String> masterNameList = getMasterNameListFromNutCrackerConf(appId);
        if (CollectionUtils.isNotEmpty(masterNameList)) {
            return true;
        }
        return false;
    }

    private List<String> getMasterNameListFromNutCrackerConf(long appId) {
        List<List<String>> appNutCrackerMasterList = getFullInstanceListFromNutCrackerConf(appId);
        if (CollectionUtils.isEmpty(appNutCrackerMasterList)) {
            return Collections.emptyList();
        }
        List<String> nutCrackerMasterList = appNutCrackerMasterList.get(0);
        List<String> masterNameList = new ArrayList<String>();
        for (String nutCrackerMaster : nutCrackerMasterList) {
            String[] arr = nutCrackerMaster.split("\\s+");
            masterNameList.add(arr[arr.length - 1].trim());
        }
        return masterNameList;
    }

    public List<List<String>> getFullInstanceListFromNutCrackerConf(long appId) {
        Map<String, List<String>> appNutCrackerMasterMap = getAppNutCrackerMasterList(appId);
        if (MapUtils.isEmpty(appNutCrackerMasterMap)) {
            logger.error(BaseTask.marker, "appId {} appNutCrackerMasterListMap is empty", appId);
            return Collections.emptyList();
        }
        List<String> ipPortList = new ArrayList<String>();
        List<List<String>> appNutCrackerMasterList = new ArrayList<List<String>>();
        for (Entry<String, List<String>> entry : appNutCrackerMasterMap.entrySet()) {
            ipPortList.add(entry.getKey());
            appNutCrackerMasterList.add(entry.getValue());
        }
        for (int i = 0; i < appNutCrackerMasterList.size() - 1; i++) {
            List<String> appNutCrackerMasterList1 = appNutCrackerMasterList.get(i);
            List<String> appNutCrackerMasterList2 = appNutCrackerMasterList.get(i + 1);
            if (appNutCrackerMasterList1.size() != appNutCrackerMasterList2.size()) {
                logger.error(BaseTask.marker, "{} and {} config size is not same", ipPortList.get(i), ipPortList.get(i + 1));
                return Collections.emptyList();
            }
            for (int j = 0; j < appNutCrackerMasterList1.size(); j++) {
                if (!appNutCrackerMasterList1.get(j).trim().equals(appNutCrackerMasterList2.get(j).trim())) {
                    logger.error(BaseTask.marker, "{} and {} config content is not same", ipPortList.get(i), ipPortList.get(i + 1));
                    return Collections.emptyList();
                }
            }
        }
        return appNutCrackerMasterList;
    }

    /**
     * 获取所有在线的proxy配置
     *
     * @param appId
     * @return
     */
    private Map<String, List<String>> getAppNutCrackerMasterList(long appId) {
        Map<String, List<String>> resultMap = new HashMap<String, List<String>>();
        List<InstanceInfo> instanceInfoList = appService.getAppInstanceByType(appId, InstanceTypeEnum.NUTCRACKER);
        for (InstanceInfo instanceInfo : instanceInfoList) {
            if (!instanceInfo.isOnline()) {
                continue;
            }
            String host = instanceInfo.getIp();
            int port = instanceInfo.getPort();
            String remoteBasePath = machineCenter.getInstanceRemoteBasePath(appId, instanceInfo.getPort(),
                    InstanceTypeEnum.NUTCRACKER);
            String confPath = MachineProtocol.getConfPath(remoteBasePath) + "/" + RedisProtocol.getNutCrackerConfName();
            String commandResult = machineCenter.executeShell(instanceInfo.getIp(), "cat " + confPath);
            if (StringUtils.isBlank(commandResult)) {
                logger.error(BaseTask.marker, "appId {} {}:{} nutcrack conf {} is empty", appId, host, port, confPath);
                return Collections.emptyMap();
            }
            List<String> masterNameList = new ArrayList<String>();
            String[] lines = commandResult.split("\n");
            for (String line : lines) {
                if (StringUtils.isBlank(line)) {
                    continue;
                }
                if (!line.contains("-")) {
                    continue;
                }
                if (line.split(":").length < 2) {
                    continue;
                }
                masterNameList.add(line);
            }
            resultMap.put(host + ":" + port, masterNameList);
        }
        return resultMap;
    }

    @Override
    public List<InstanceInfo> checkNutCrackerHashIsSame(long appId, boolean isDelete) {
        return null;
    }

    @Override
    public List<InstanceInfo> checkInstanceModule(long appId) {

        // 实例列表
        List<InstanceInfo> instanceList = appService.getAppInstanceInfo(appId);
        if (!CollectionUtils.isEmpty(instanceList)) {
            //增加实例在线过滤，避免查询已下线实例造成错误
            instanceList = instanceList.stream().filter(instanceInfo -> InstanceStatusEnum.GOOD_STATUS.getStatus() == instanceInfo.getStatus()).collect(Collectors.toList());
            for (InstanceInfo instanceInfo : instanceList) {
                if (!CollectionUtils.isEmpty(instanceList)) {
                    String host = instanceInfo.getIp();
                    int port = instanceInfo.getPort();
                    int type = instanceInfo.getType();
                    Jedis jedis = null;
                    try {
                        if (type == ConstUtils.CACHE_REDIS_STANDALONE || type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
                            jedis = getJedis(appId, host, port);
                            List<redis.clients.jedis.Module> modules = jedis.moduleList();
                            modules = modules.stream().sorted(Comparator.comparing(redis.clients.jedis.Module::getName)).collect(Collectors.toList());
                            instanceInfo.setModules(modules);
                            logger.info("checkInstanceModule {}:{} module info :{}", host, port, modules);
                        }
                    } catch (Exception e) {
                        logger.error("checkInstanceModule {}:{} error , message:{}", host, port, e.getMessage(), e);
                    } finally {
                        if (jedis != null) {
                            jedis.close();
                        }
                    }
                }
            }
        }
        return instanceList;
    }

    @Override
    public Map loadModule(long appId, int versionId) {

        Map<String, Object> resultMap = new HashMap<String, Object>();
        int status = SuccessEnum.SUCCESS.value();
        String message = "";

        // 装载模块
        String so_name = "";
        try {
            ModuleVersion moduleVersion = moduleService.getModuleVersionById(versionId);
            // 验证是否存在
            String soPath = moduleVersion.getSoPath();
            so_name = soPath.substring(soPath.lastIndexOf("/") + 1);
            String check_command = String.format("ls -l %s | grep %s | wc -l", ConstUtils.MODULE_BASE_PATH, so_name);
            String download_command = String.format("mkdir -p %s && cd %s && wget %s && chmod +x *.so", ConstUtils.MODULE_BASE_PATH, ConstUtils.MODULE_BASE_PATH, soPath);
            // module load path
            String module_path = ConstUtils.MODULE_BASE_PATH + so_name;

            List<String> moduleLoadConfigList = this.getModuleLoadConfig(appId, moduleVersion.getId());
            List<String> moduleConfigList = new ArrayList<>();
            moduleConfigList.addAll(moduleLoadConfigList);
            moduleConfigList.add(0, Protocol.Keyword.LOAD.name());
            moduleConfigList.add(1, module_path);
            String[] commandArgs = moduleConfigList.toArray(new String[moduleConfigList.size()]);
            List<InstanceInfo> instanceList = appService.getAppInstanceInfo(appId);
            boolean existFlag = false;
            if (!CollectionUtils.isEmpty(instanceList)) {
                for (InstanceInfo instanceInfo : instanceList) {
                    if (!instanceInfo.isOffline()) {
                        String host = instanceInfo.getIp();
                        int port = instanceInfo.getPort();
                        int type = instanceInfo.getType();
                        //检测并下载组件
                        checkAndDownloadModule(host, check_command, download_command);
                        Jedis jedis = null;
                        try {
                            if (type == ConstUtils.CACHE_REDIS_STANDALONE || type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
                                jedis = getJedis(appId, host, port);
                                // 装载模块
                                List<redis.clients.jedis.Module> modules = jedis.moduleList();
                                for (redis.clients.jedis.Module module : modules) {
                                    if (so_name.contains(module.getName())) {
                                        existFlag = true;
                                    }
                                }
                                if (existFlag) {
                                    existFlag = false;
                                    continue;
                                }
                                Object result = jedis.sendCommand(Protocol.Command.MODULE, commandArgs);
                                logger.info(" {}:{} load module path:{} result:{}", host, port, module_path, result);
                                //写配置文件
                                String newModulePath = module_path;
                                if (CollectionUtils.isNotEmpty(moduleLoadConfigList)) {
                                    String loadConfigStr = moduleLoadConfigList.stream().collect(Collectors.joining(" "));
                                    newModulePath = newModulePath + " " + loadConfigStr;
                                }
                                refreshConfig(appId, host, port, newModulePath);
//                                }
                            }
                        } catch (Exception e) {
                            logger.error(" {}:{} load module path:{} error , message:{}", host, port, module_path, e.getMessage(), e);
                            status = SuccessEnum.ERROR.value();
                            message += String.format("%s:%s load module:%s error \n", host, port, module_path);
                        } finally {
                            if (jedis != null) {
                                jedis.close();
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.error("appid:{} load moduleName :{} error :{}", appId, so_name, e.getMessage());
            status = SuccessEnum.FAIL.value();
        }

        resultMap.put("status", status);
        resultMap.put("so_name", so_name);
        resultMap.put("message", message);
        return resultMap;
    }

    // 校验是否存在并自动下载so
    @Override
    public void checkAndDownloadModule(String ip, List<ModuleVersion> moduleList) {
        try {
            for (ModuleVersion moduleVersion : moduleList) {
                String soPath = moduleVersion.getSoPath();
                String so_name = soPath.substring(soPath.lastIndexOf("/") + 1);
                String check_command = String.format("ls -l %s | grep %s | wc -l", ConstUtils.MODULE_BASE_PATH, so_name);
                String download_command = String.format("mkdir -p %s && cd %s && wget %s && chmod +x *.so", ConstUtils.MODULE_BASE_PATH, ConstUtils.MODULE_BASE_PATH, soPath);
                String result = sshService.execute(ip, check_command);
                logger.info("checkAndDownloadModule check_command:{} result:{}", check_command, result);
                if ("0".equals(result)) {
                    // download to module path
                    String download_res = sshService.execute(ip, download_command);
                    logger.info("download_command:{} result:{}", download_command, download_res);
                }
            }
        } catch (SSHException e) {
            logger.error("checkAndDownloadModule ip:{} error :{}", ip, e.getMessage(), e);
            throw new RuntimeException(String.format("machine: %s redis module checkAndDownloadModule error", ip));
        }
    }

    public boolean refreshConfig(long appid, String host, int port, String modulePath) {

        try {
            AppDesc appDesc = appService.getByAppId(appid);
            boolean iscluster = false;
            if (appDesc.getType() == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
                iscluster = true;
            }
            String configName = RedisProtocol.getConfig(port, iscluster);
            String filePath = MachineProtocol.CONF_DIR + configName;
            if (machineCenter.isK8sMachine(host)) {
                filePath = MachineProtocol.getK8sConfDir(host) + configName;
            }

            String cmd = String.format("echo \"loadmodule %s\" >> %s", modulePath, filePath);

            String result = sshService.execute(host, cmd);
            logger.info("appid:{} {}:{} load module:{} refresh config result:{}", appid, host, port, modulePath, result);

        } catch (Exception e) {
            logger.error("appid:{} {}:{} load module:{} refresh config error :{}", appid, host, port, modulePath, e.getMessage(), e);
            return false;
        }
        return true;
    }

    // 自动下载so
    public void checkAndDownloadModule(String ip, String check_command, String download_command) {
        try {
            String result = sshService.execute(ip, check_command);
            logger.info("checkAndDownloadModule check_command:{} result:{}", check_command, result);
            if ("0".equals(result)) {
                // download to module path
                String download_res = sshService.execute(ip, download_command);
                logger.info("download_command:{} result:{}", download_command, download_res);
            }
        } catch (SSHException e) {
            logger.error("checkAndDownloadModule ip:{} error :{}", ip, e.getMessage(), e);
        }
    }

    @Override
    public boolean checkAndLoadModule(long appId, String ip, int port) {
        List<ModuleVersion> moduleList = appService.getAppToModuleList(appId);
        if (CollectionUtils.isNotEmpty(moduleList)) {
            this.checkAndDownloadModule(ip, moduleList);
            Jedis currentJedis = null;
            try {
                //变更redis实例
                currentJedis = getJedis(appId, ip, port);
                // 未load module
                if (!CollectionUtils.isEmpty(moduleList)) {
                    for (ModuleVersion moduleVersion : moduleList) {
                        List<String> moduleLoadConfigList = this.getModuleLoadConfig(appId, moduleVersion.getId());
                        List<String> moduleConfigList = new ArrayList<>();
                        String soPath = moduleVersion.getSoPath();
                        String moduleName = soPath.substring(soPath.lastIndexOf("/") + 1);
                        // 装载redis插件
                        String modulePath = String.format("%s%s", ConstUtils.MODULE_BASE_PATH, moduleName);
                        moduleConfigList.addAll(moduleLoadConfigList);
                        moduleConfigList.add(0, Protocol.Keyword.LOAD.name());
                        moduleConfigList.add(1, modulePath);
                        Object result = currentJedis.sendCommand(Protocol.Command.MODULE, moduleConfigList.toArray(new String[moduleConfigList.size()]));
                        logger.info(" {}:{} load module path:{} result:{}", ip, port, modulePath, result);
                        // 写配置文件
                        if (CollectionUtils.isNotEmpty(moduleLoadConfigList)) {
                            String loadConfigStr = moduleLoadConfigList.stream().collect(Collectors.joining(" "));
                            modulePath = modulePath + " " + loadConfigStr;
                        }
                        refreshConfig(appId, ip, port, modulePath);
                    }
                }
            } catch (Exception e) {
                logger.error(" {}:{} load module error , message:{}", ip, port, e.getMessage(), e);
                return false;
            } finally {
                if (currentJedis != null) {
                    currentJedis.close();
                }
            }

        }
        return true;
    }

    /**
     * 获取模块默认配置并返回 配置文件中的模块格式
     *
     * @param appId
     * @param moduleList
     * @return
     */
    @Override
    public List<String> getLoadModuleDefaultConfig(long appId, List<ModuleVersion> moduleList) {
        List<String> defaultConfigList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(moduleList)) {
            moduleList = appService.getAppToModuleList(appId);
        }
        if (CollectionUtils.isNotEmpty(moduleList)) {
            try {
                //变更redis实例
                // 未load module
                if (!CollectionUtils.isEmpty(moduleList)) {
                    for (ModuleVersion moduleVersion : moduleList) {
                        List<String> moduleConfigList = this.getModuleLoadConfig(appId, moduleVersion.getId());
                        String soPath = moduleVersion.getSoPath();
                        String moduleName = soPath.substring(soPath.lastIndexOf("/") + 1);
                        // 装载redis插件
                        String modulePath = String.format("%s%s", ConstUtils.MODULE_BASE_PATH, moduleName);
                        logger.info(" {} get load module path:{} default config", appId, modulePath);
                        // 写配置文件
                        if (CollectionUtils.isNotEmpty(moduleConfigList)) {
                            String loadConfigStr = moduleConfigList.stream().collect(Collectors.joining(" "));
                            modulePath = modulePath + " " + loadConfigStr;
                        }
                        String moduleConfig = String.format("loadmodule %s", modulePath);
                        defaultConfigList.add(moduleConfig);
                    }
                }
            } catch (Exception e) {
                logger.error(" {} get load module default config error , message:{}", appId, e.getMessage(), e);
            }
        }
        return defaultConfigList;
    }

    private List<String> getModuleLoadConfig(Long appId, Integer versionId) {
        List<String> configList = new ArrayList<>();
        List<RedisModuleConfig> moduleConfigs = redisModuleConfigDao.getModuleConfigByVersionId(versionId);
        if (CollectionUtils.isNotEmpty(moduleConfigs)) {
            Map<String, String> loadConfigMap = moduleConfigs.stream().filter(moduleConfig -> moduleConfig.getConfigType() == 1)
                    .collect(Collectors.toMap(moduleConfig -> moduleConfig.getConfigKey(), moduleConfig -> moduleConfig.getConfigValue()));
            if (loadConfigMap != null && loadConfigMap.size() > 0) {
                AppDesc appDesc = appDao.getAppDescById(appId);
                Set<Entry<String, String>> entries = loadConfigMap.entrySet();
                entries.stream().filter(entry -> (entry.getValue() != null && entry.getValue().contains("${password}")))
                        .forEach(entry -> entry.setValue(appDesc.getAppPassword()));
                entries.forEach(entry -> {
                    configList.add(entry.getKey());
                    if (entry.getValue() == null) {
                        entry.setValue("");
                    }
                    configList.add(entry.getValue());
                });
            }
        }
        return configList;
    }

    @Override
    public Map unloadModule(long appId, String moduleName) {
        Map<String, Object> resultMap = new HashMap<String, Object>();
        int status = SuccessEnum.SUCCESS.value();
        String message = "";
        try {
            List<InstanceInfo> instanceList = appService.getAppInstanceInfo(appId);
            if (!CollectionUtils.isEmpty(instanceList)) {
                for (InstanceInfo instanceInfo : instanceList) {
                    if (!instanceInfo.isOffline()) {
                        String host = instanceInfo.getIp();
                        int port = instanceInfo.getPort();
                        int type = instanceInfo.getType();
                        Jedis jedis = null;
                        try {
                            if (type == ConstUtils.CACHE_REDIS_STANDALONE || type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
                                jedis = getJedis(appId, host, port);
                                List<redis.clients.jedis.Module> modules = jedis.moduleList();
                                if (!CollectionUtils.isEmpty(modules)) {
                                    for (redis.clients.jedis.Module module : modules) {
                                        if (module.getName().equals(moduleName)) {
                                            String result = jedis.moduleUnload(module.getName());
                                            logger.info("checkInstanceModule {}:{} unload module:{} result:{}", host, port, moduleName, result);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.error("checkInstanceModule {}:{} unload module:{} error , message:{}", host, port, moduleName, e.getMessage(), e);
                            status = SuccessEnum.ERROR.value();
                            message += String.format("%s:%s unload module:%s error \n", host, port, moduleName);
                        } finally {
                            if (jedis != null) {
                                jedis.close();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("appid:{} unload moduleName :{} error :{}", appId, moduleName, e.getMessage());
            status = SuccessEnum.FAIL.value();
        }
        resultMap.put("status", status);
        resultMap.put("message", message);
        return resultMap;
    }

    private class RedisKeyCallable extends KeyCallable<Boolean> {
        private final long appId;
        private final long collectTime;
        private final String host;
        private final int port;
        private final Map<RedisConstant, Map<String, Object>> infoMap;
        private final Map<String, Object> clusterInfoMap;

        private RedisKeyCallable(long appId, long collectTime, String host, int port,
                                 Map<RedisConstant, Map<String, Object>> infoMap, Map<String, Object> clusterInfoMap) {
            super(buildFutureKey(appId, collectTime, host, port));
            this.appId = appId;
            this.collectTime = collectTime;
            this.host = host;
            this.port = port;
            this.infoMap = infoMap;
            this.clusterInfoMap = clusterInfoMap;
        }

        @Override
        public Boolean execute() {
            //比对currentInfoMap和lastInfoMap,计算差值
            long lastCollectTime = ScheduleUtil.getLastCollectTime(collectTime);
            Map<String, Object> lastInfoMap = instanceStatsCenter
                    .queryStandardInfoMap(lastCollectTime, host, port, ConstUtils.REDIS);

            if (lastInfoMap == null || lastInfoMap.isEmpty()) {
                logger.debug("[redis-lastInfoMap] : lastCollectTime = {} appId={} host:port = {}:{} is null",
                        lastCollectTime, appId, host, port);
            }
            //基本统计累加差值
            Table<RedisConstant, String, Long> baseDiffTable = getAccumulationDiff(infoMap, lastInfoMap);
            fillAccumulationMap(infoMap, baseDiffTable);

            //命令累加差值
            Table<RedisConstant, String, Long> commandDiffTable = getCommandsDiff(infoMap, lastInfoMap);
            fillAccumulationMap(infoMap, commandDiffTable);

            //内存碎片率差值计算
            Table<RedisConstant, String, Double> otherDiffTable = getDoubleAccumulationDiff(infoMap, lastInfoMap);
            fillDoubleAccumulationMap(infoMap, otherDiffTable);
            fillMemFragRatioMap(infoMap);
            fillOpsSnapshotMap(infoMap);

            Map<String, Object> currentInfoMap = new LinkedHashMap<String, Object>();
            for (Map.Entry<RedisConstant, Map<String, Object>> entry : infoMap.entrySet()) {
                currentInfoMap.put(entry.getKey().getValue(), entry.getValue());
            }
            currentInfoMap.put(ConstUtils.COLLECT_TIME, collectTime);
            instanceStatsCenter.saveStandardStats(currentInfoMap, clusterInfoMap, host, port, ConstUtils.REDIS);

            // 更新实例在db中的状态
            InstanceStats instanceStats = getInstanceStats(appId, host, port, infoMap);
            if (instanceStats != null) {
                instanceStatsDao.updateInstanceStats(instanceStats);
            }

            // 顺带落一份风险评估用的窄表数据，复用当前 infoMap，不额外发起 Redis 连接
            riskMetricCollector.collect(instanceDao.getAllInstByIpAndPort(host, port), infoMap, clusterInfoMap,
                    collectTime);

            BooleanEnum isMaster = isMaster(infoMap);
            if (isMaster == BooleanEnum.TRUE) {
                Table<RedisConstant, String, Long> diffTable = HashBasedTable.create();
                diffTable.putAll(baseDiffTable);
                diffTable.putAll(commandDiffTable);

                long allCommandCount = 0L;
                //更新命令统计
                List<AppCommandStats> commandStatsList = getCommandStatsList(appId, collectTime, diffTable);
                for (AppCommandStats commandStats : commandStatsList) {
                    //排除无效命令且存储有累加的数据
                    if (RedisExcludeCommand.isExcludeCommand(commandStats.getCommandName())
                            || commandStats.getCommandCount() <= 0L) {
                        continue;
                    }
                    allCommandCount += commandStats.getCommandCount();
                    try {
                        // todo 数据库(on duplicate key update)竞争优化
                        appStatsDao.mergeMinuteCommandStatus(commandStats);
                        appStatsDao.mergeHourCommandStatus(commandStats);
                    } catch (Exception e) {
                        logger.error(e.getMessage() + appId, e);
                    }
                }
                //写入app分钟统计
                AppStats appStats = getAppStats(appId, collectTime, diffTable, infoMap);
                try {
                    appStats.setCommandCount(allCommandCount);
                    // todo 数据库(on duplicate key update)竞争优化
                    appStatsDao.mergeMinuteAppStats(appStats);
                    appStatsDao.mergeHourAppStats(appStats);
                } catch (Exception e) {
                    logger.error(e.getMessage() + appId, e);
                }
                logger.debug("collect redis info done, appId: {}, instance: {}:{}, time: {}", appId, host, port,
                        collectTime);
            }

            return true;
        }
    }
}
