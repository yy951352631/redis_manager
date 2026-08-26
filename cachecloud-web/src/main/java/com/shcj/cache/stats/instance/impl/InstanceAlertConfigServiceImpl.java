package com.shcj.cache.stats.instance.impl;

import com.shcj.cache.alert.EmailComponent;
import com.shcj.cache.alert.bean.AlertConfigBaseData;
import com.shcj.cache.alert.service.AppAggregateAlertService;
import com.shcj.cache.alert.service.InstanceAliveStateService;
import com.shcj.cache.alert.strategy.*;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.InstanceAlertConfigDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.dao.StandardStatsDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.redis.enums.AlertConfigScopeEnum;
import com.shcj.cache.redis.enums.InstanceAlertStatusEnum;
import com.shcj.cache.redis.enums.InstanceAlertTypeEnum;
import com.shcj.cache.redis.enums.RedisAlertConfigEnum;
import com.shcj.cache.stats.instance.InstanceAlertConfigService;
import com.shcj.cache.async.AsyncThreadPoolFactory;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.util.EnvUtil;
import com.shcj.cache.web.enums.AlertTypeEnum;
import com.shcj.cache.web.enums.ImportantLevelTypeEnum;
import com.shcj.cache.web.service.AppAlertRecordService;
import com.shcj.cache.web.service.UserService;
import com.shcj.cache.web.util.FreemakerUtils;
import freemarker.template.Configuration;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author leifu
 * @Date 2017年5月19日
 * @Time 下午2:16:36
 */
@Service("instanceAlertConfigService")
public class InstanceAlertConfigServiceImpl implements InstanceAlertConfigService {

    private Logger logger = LoggerFactory.getLogger(InstanceAlertConfigServiceImpl.class);

    /** 存活探测的当前值取值，与配置里的 alert_value 做字符串比较 */
    private static final String ALIVE_OK = "ok";
    private static final String ALIVE_FAIL = "探测失败";

    /** 哨兵 PING 并发探测的总超时：单次 isSentinelRun 最多重试 3 次 × 2s */
    private static final int SENTINEL_PROBE_TIMEOUT_SECONDS = 15;

    @Autowired
    private InstanceAlertConfigDao instanceAlertConfigDao;
    @Autowired
    private StandardStatsDao standardStatsDao;
    @Autowired
    private InstanceAliveStateService instanceAliveStateService;
    @Autowired
    private AppAggregateAlertService appAggregateAlertService;
    @Autowired
    private RedisCenter redisCenter;
    @Autowired
    private InstanceDao instanceDao;
    @Autowired
    private EmailComponent emailComponent;
    @Autowired
    private Configuration configuration;
    @Autowired
    private AppDao appDao;
    @Autowired
    private UserService userService;
    @Autowired
    private Environment environment;
    @Autowired
    private AppAlertRecordService appAlertRecordService;

    private static Map<RedisAlertConfigEnum, AlertConfigStrategy> alertConfigStrategyMap = new HashMap<RedisAlertConfigEnum, AlertConfigStrategy>();

    static {
        alertConfigStrategyMap.put(RedisAlertConfigEnum.aof_current_size, new AofCurrentSizeAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.client_biggest_input_buf, new ClientBiggestInputBufAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.client_longest_output_list, new ClientLongestOutputListAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.instantaneous_ops_per_sec, new InstantaneousOpsPerSecAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.latest_fork_usec, new LatestForkUsecAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.mem_fragmentation_ratio, new MemFragmentationRatioAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.rdb_last_bgsave_status, new RdbLastBgsaveStatusAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.minute_aof_delayed_fsync, new MinuteAofDelayedFsyncAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.minute_rejected_connections, new MinuteRejectedConnectionsAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.minute_sync_partial_err, new MinuteSyncPartialErrAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.minute_sync_partial_ok, new MinuteSyncPartialOkAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.minute_sync_full, new MinuteSyncFullAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.minute_total_net_input_bytes, new MinuteTotalNetInputMBytesAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.minute_total_net_output_bytes, new MinuteTotalNetOutputMBytesAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.master_slave_offset_diff, new MasterSlaveOffsetAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.cluster_state, new ClusterStateAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.cluster_slots_ok, new ClusterSlotsOkAlertStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.used_cpu_sys, new MinuteUsedCpuSysStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.used_cpu_user, new MinuteUsedCpuUserStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.used_cpu_sys_children, new MinuteUsedCpuSysChStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.used_cpu_user_children, new MinuteUsedCpuUserChStrategy());
        alertConfigStrategyMap.put(RedisAlertConfigEnum.other_default_common_config, new DefaultCommonAlertStrategy());
    }

    @Override
    public List<InstanceAlertConfig> getAll() {
        try {
            return instanceAlertConfigDao.getAll();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public int save(InstanceAlertConfig instanceAlertConfig) {
        try {
            return instanceAlertConfigDao.save(instanceAlertConfig);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return -1;
        }
    }

    @Override
    public int batchSave(List<InstanceAlertConfig> instanceAlertConfigList) {
        try {
            return instanceAlertConfigDao.batchSave(instanceAlertConfigList);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return -1;
        }
    }

    @Override
    public InstanceAlertConfig get(int id) {
        try {
            return instanceAlertConfigDao.get(id);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public int remove(int id) {
        try {
            return instanceAlertConfigDao.remove(id);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return -1;
        }
    }

    @Override
    public List<InstanceAlertConfig> getByType(int type) {
        try {
            return instanceAlertConfigDao.getByType(type);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public int getImportantLevelByAlertConfigAndCompareType(String alertConfig, int compareType) {
        try {
            List<InstanceAlertConfig> configList = instanceAlertConfigDao.getByAlertConfigAndType(alertConfig, InstanceAlertTypeEnum.ALL_ALERT.getValue());
            for (InstanceAlertConfig instanceAlertConfig : configList) {
                if (instanceAlertConfig.getStatus() == InstanceAlertStatusEnum.YES.getValue() && compareType == instanceAlertConfig.getCompareType()) {
                    return instanceAlertConfig.getImportantLevel();
                }
            }
            if (CollectionUtils.isNotEmpty(configList)) {
                return configList.get(0).getImportantLevel();
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return 0;
    }

    @Override
    public void update(long id, String alertValue, int checkCycle, int compareType, int importantLevel) {
        instanceAlertConfigDao.update(id, alertValue, checkCycle, compareType, importantLevel);
    }

    @Override
    public void updateImportantLevel(String alertConfig, int compareType, int importantLevel) {
        instanceAlertConfigDao.updateImportantLevel(alertConfig, compareType, importantLevel);
    }

    @Override
    public void updateLastCheckTime(long id, Date lastCheckTime) {
        try {
            instanceAlertConfigDao.updateLastCheckTime(id, lastCheckTime);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Override
    public void monitorLastMinuteAllInstanceInfo() {
        long startTime = System.currentTimeMillis();
        // 1.全部和特殊实例报警配置
        List<InstanceAlertConfig> commonInstanceAlertConfigList = getByType(InstanceAlertTypeEnum.ALL_ALERT.getValue());
        List<InstanceAlertConfig> specialInstanceAlertConfigList = getByType(InstanceAlertTypeEnum.INSTANCE_ALERT.getValue());
        //1.2查询应用报警配置
        List<InstanceAlertConfig> appInstanceAlertConfigList = getByType(InstanceAlertTypeEnum.APP_ALERT.getValue());

        // 2.所有实例信息
        List<InstanceInfo> allInstanceInfoList = instanceDao.getAllInsts();
        if (CollectionUtils.isEmpty(allInstanceInfoList)) {
            return;
        }

        // 2.1 需按集群遍历评估的项（节点内存/连接数、集群命中率）不能展开到实例维度，先摘出来
        // 2.0 status=0 表示该配置已停用，此前这一列从未被判断过
        removeDisabled(commonInstanceAlertConfigList);
        removeDisabled(specialInstanceAlertConfigList);
        removeDisabled(appInstanceAlertConfigList);

        List<InstanceAlertConfig> aggregateConfigList = new ArrayList<>();
        takeOutAggregateConfigs(commonInstanceAlertConfigList, aggregateConfigList);
        takeOutAggregateConfigs(appInstanceAlertConfigList, aggregateConfigList);

        List<InstanceAlertConfig> transferAppInstanceAlertConfigList = new ArrayList<>();
        List<InstanceInfo> appInstanceInfo = null;
        for (InstanceAlertConfig instanceAlertConfig : appInstanceAlertConfigList) {
            appInstanceInfo = allInstanceInfoList.stream().filter(instanceInfo -> instanceInfo.getAppId() == instanceAlertConfig.getInstanceId()).collect(Collectors.toList());
            addAppInstanceToTransferList(transferAppInstanceAlertConfigList, instanceAlertConfig, appInstanceInfo);
        }
        if (CollectionUtils.isNotEmpty(transferAppInstanceAlertConfigList)) {
            specialInstanceAlertConfigList.addAll(transferAppInstanceAlertConfigList);
        }

        List<InstanceAlertConfig> allInstanceAlertConfigList = new ArrayList<InstanceAlertConfig>();
        allInstanceAlertConfigList.addAll(commonInstanceAlertConfigList);
        allInstanceAlertConfigList.addAll(specialInstanceAlertConfigList);
        if (CollectionUtils.isEmpty(allInstanceAlertConfigList) && CollectionUtils.isEmpty(aggregateConfigList)) {
            return;
        }
        // 3. 取上1分钟Redis实例统计信息
        Date currentTime = new Date();
        Date beginTime = DateUtils.addMinutes(currentTime, -2);
        Date endTime = DateUtils.addMinutes(currentTime, -1);
        Map<String, StandardStats> standardStatMap = getStandardStatsMap(beginTime, endTime);
        if (MapUtils.isEmpty(standardStatMap)) {
            // 全量为空说明采集侧整体异常，而不是所有节点同时挂掉；此时按节点逐个报警只会制造噪音
            logger.error("standardStatMap is empty, skip this round. check redis stats collect job");
            return;
        }

        List<InstanceAlertValueResult> instanceAlertValueResultList = new ArrayList<InstanceAlertValueResult>();

        // 3.1 存活探测：以「上一分钟是否采集到 INFO」为准，并同步维护节点状态与故障记录。
        //     状态维护与报警配置解耦——节点列表/故障页依赖 status，删掉 instance_alive 配置不应让它们失效。
        Map<Integer, Boolean> aliveMap = refreshAliveStates(allInstanceInfoList, standardStatMap);

        // 3.2 关闭了「集群报警通知」的集群不产生任何告警。
        //     注意过滤发生在 refreshAliveStates 之后：节点状态与故障记录属于运行状态，
        //     必须继续维护，否则关掉报警的集群会在节点列表里一直停在旧状态。
        Map<Long, Boolean> alertEnabledCache = new HashMap<Long, Boolean>();
        List<InstanceInfo> alertInstanceInfoList = filterAlertEnabled(allInstanceInfoList, alertEnabledCache);

        // 4.按作用域分派：存活探测不依赖 INFO 内容，需要在「取不到 standardStats 就跳过」之前判定
        List<InstanceAlertConfig> aliveConfigList = new ArrayList<>();
        List<InstanceAlertConfig> infoConfigList = new ArrayList<>();
        for (InstanceAlertConfig config : allInstanceAlertConfigList) {
            if (RedisAlertConfigEnum.getScope(config.getAlertConfig()) == AlertConfigScopeEnum.INSTANCE_STATE) {
                aliveConfigList.add(config);
            } else {
                infoConfigList.add(config);
            }
        }
        instanceAlertValueResultList.addAll(
                checkInstanceAlive(aliveConfigList, alertInstanceInfoList, aliveMap, currentTime));
        checkAppAggregate(aggregateConfigList, alertInstanceInfoList, currentTime);

        for (InstanceAlertConfig instanceAlertConfig : infoConfigList) {
            if (!checkInCycle(instanceAlertConfig)) {
                continue;
            }
            // 特殊配置只作用于单个实例；这里必须新建列表，直接复用 allInstanceInfoList
            // 会因为引用别名把后续配置要遍历的全量实例清空
            List<InstanceInfo> tempInstanceInfoList = alertInstanceInfoList;
            if (instanceAlertConfig.isSpecail()) {
                InstanceInfo instanceInfo = null;
                if (instanceAlertConfig.getInstanceInfo() == null) {
                    instanceInfo = instanceDao.getInstanceInfoById(instanceAlertConfig.getInstanceId());
                } else {
                    instanceInfo = instanceAlertConfig.getInstanceInfo();
                }
                if (instanceInfo == null || !isAppAlertEnabled(instanceInfo.getAppId(), alertEnabledCache)) {
                    continue;
                }
                tempInstanceInfoList = Collections.singletonList(instanceInfo);
            }
            for (InstanceInfo instanceInfo : tempInstanceInfoList) {
                List<InstanceAlertValueResult> InstanceAlertValueResultTempList = dealInstanceAlert(specialInstanceAlertConfigList, instanceAlertConfig, instanceInfo, standardStatMap, currentTime);
                if (CollectionUtils.isNotEmpty(InstanceAlertValueResultTempList)) {
                    instanceAlertValueResultList.addAll(InstanceAlertValueResultTempList);
                }
            }
            // 更新配置最后检测时间
            updateLastCheckTime(instanceAlertConfig.getId(), currentTime);
        }
        if (CollectionUtils.isNotEmpty(instanceAlertValueResultList)) {
            // 发送邮件
            sendInstanceAlertEmail(beginTime, endTime, instanceAlertValueResultList);
        }
        long costTime = System.currentTimeMillis() - startTime;
        if (costTime > 20000) {
            logger.warn("monitorLastMinuteAllInstanceInfo cost {} ms", costTime);
        }
    }

    /**
     * 剔除已关闭「集群报警通知」（app_desc.is_access_monitor = 0）的集群下的实例。
     *
     * <p>该开关是集群级的告警总闸：关闭后这个集群不再产生任何报警记录，
     * 与具体配置了哪些报警项无关。</p>
     */
    private List<InstanceInfo> filterAlertEnabled(List<InstanceInfo> instanceInfoList,
                                                  Map<Long, Boolean> alertEnabledCache) {
        if (CollectionUtils.isEmpty(instanceInfoList)) {
            return Collections.emptyList();
        }
        List<InstanceInfo> result = new ArrayList<InstanceInfo>();
        for (InstanceInfo instanceInfo : instanceInfoList) {
            if (isAppAlertEnabled(instanceInfo.getAppId(), alertEnabledCache)) {
                result.add(instanceInfo);
            }
        }
        if (result.size() != instanceInfoList.size()) {
            logger.info("skip alert for {} instances whose app disabled cluster alert",
                    instanceInfoList.size() - result.size());
        }
        return result;
    }

    /**
     * 集群是否启用了监控告警。查不到集群时按启用处理——宁可多报也不要静默漏报。
     *
     * <p>缓存由调用方按轮传入：一轮判定里同一集群会被问到很多次，每次都查库没有必要；
     * 用局部 Map 而不是 ThreadLocal，避免在 Quartz 线程池里残留。</p>
     */
    private boolean isAppAlertEnabled(long appId, Map<Long, Boolean> alertEnabledCache) {
        Boolean cached = alertEnabledCache.get(appId);
        if (cached != null) {
            return cached;
        }
        AppDesc appDesc = appDao.getAppDescById(appId);
        boolean enabled = appDesc == null || appDesc.getIsAccessMonitor() == 1;
        alertEnabledCache.put(appId, enabled);
        return enabled;
    }

    /**
     * 将应用报警转化为应用下实例报警
     *
     * @param transferAppInstanceAlertConfigList 转换后结果存储
     * @param instanceAlertConfig                应用报警配置
     * @param appInstanceInfo                    应用下实例信息列表
     */
    private void addAppInstanceToTransferList(List<InstanceAlertConfig> transferAppInstanceAlertConfigList, InstanceAlertConfig instanceAlertConfig, List<InstanceInfo> appInstanceInfo) {
        InstanceAlertConfig appInstanceAlertConfig = null;
        for (InstanceInfo instanceInfo : appInstanceInfo) {
            appInstanceAlertConfig = new InstanceAlertConfig();
            try {
                BeanUtils.copyProperties(appInstanceAlertConfig, instanceAlertConfig);
            } catch (Exception e) {
                logger.error("addAppInstanceToTransferList error:", e);
            }
            appInstanceAlertConfig.setInstanceId(instanceInfo.getId());
            appInstanceAlertConfig.setInstanceInfo(instanceInfo);
            transferAppInstanceAlertConfigList.add(appInstanceAlertConfig);
        }
    }

    /**
     * 处理实例
     *
     * @param instanceAlertConfig
     * @param instanceInfo
     * @param standardStatMap
     * @param currentTime
     */
    private List<InstanceAlertValueResult> dealInstanceAlert(List<InstanceAlertConfig> specialInstanceAlertConfigList, InstanceAlertConfig instanceAlertConfig, InstanceInfo instanceInfo, Map<String, StandardStats> standardStatMap, Date currentTime) {
        if (instanceInfo.isOffline()) {
            return null;
        }
        // 单个实例的统计信息
        String hostPort = instanceInfo.getHostPort();
        StandardStats standardStats = standardStatMap.get(hostPort);
        if (standardStats == null) {
            return null;
        }
        // 判断是不是特殊实例
        InstanceAlertConfig finalInstanceConfig = filterSpecial(specialInstanceAlertConfigList, instanceAlertConfig, instanceInfo);
        // 普通配置，但finalInstanceConfig不等于instanceAlertConfig，跳过
        if (!instanceAlertConfig.isSpecail() && finalInstanceConfig.getId() != instanceAlertConfig.getId()) {
            return null;
        }
        // 是否进入检测周期
        boolean isInCycle = checkInCycle(finalInstanceConfig);
        if (!isInCycle) {
            return null;
        }
        // 枚举检测
        String alertConfig = finalInstanceConfig.getAlertConfig();
        RedisAlertConfigEnum redisAlertConfigEnum = RedisAlertConfigEnum.getRedisAlertConfig(alertConfig);
        if (redisAlertConfigEnum == null) {
            redisAlertConfigEnum = RedisAlertConfigEnum.other_default_common_config;
        }

        // 策略检测
        AlertConfigStrategy alertConfigStrategy = alertConfigStrategyMap.get(redisAlertConfigEnum);
        if (alertConfigStrategy == null) {
            return null;
        }

        // 获取基准数据
        AlertConfigBaseData alertConfigBaseData = new AlertConfigBaseData();
        alertConfigBaseData.setInstanceInfo(instanceInfo);
        alertConfigBaseData.setStandardStats(standardStats);
        // 开始检测
        try {
            return alertConfigStrategy.checkConfig(finalInstanceConfig, alertConfigBaseData);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * 按集群遍历评估的报警：先按检测周期过滤，评估后统一推进 last_check_time。
     */
    private void checkAppAggregate(List<InstanceAlertConfig> aggregateConfigList,
                                   List<InstanceInfo> allInstanceInfoList, Date currentTime) {
        if (CollectionUtils.isEmpty(aggregateConfigList)) {
            return;
        }
        List<InstanceAlertConfig> dueConfigList = new ArrayList<>();
        for (InstanceAlertConfig config : aggregateConfigList) {
            if (checkInCycle(config)) {
                dueConfigList.add(config);
            }
        }
        if (dueConfigList.isEmpty()) {
            return;
        }
        appAggregateAlertService.check(dueConfigList, allInstanceInfoList);
        for (InstanceAlertConfig config : dueConfigList) {
            updateLastCheckTime(config.getId(), currentTime);
        }
    }

    /**
     * 剔除已停用的报警配置。
     */
    private void removeDisabled(List<InstanceAlertConfig> configs) {
        if (CollectionUtils.isEmpty(configs)) {
            return;
        }
        configs.removeIf(config -> config.getStatus() != InstanceAlertStatusEnum.YES.getValue());
    }

    /**
     * 摘出按集群遍历评估的配置，避免它们被展开成实例维度配置。
     */
    private void takeOutAggregateConfigs(List<InstanceAlertConfig> source, List<InstanceAlertConfig> target) {
        if (CollectionUtils.isEmpty(source)) {
            return;
        }
        Iterator<InstanceAlertConfig> iterator = source.iterator();
        while (iterator.hasNext()) {
            InstanceAlertConfig config = iterator.next();
            if (RedisAlertConfigEnum.getScope(config.getAlertConfig()) == AlertConfigScopeEnum.APP_SCOPED) {
                target.add(config);
                iterator.remove();
            }
        }
    }

    /**
     * 判定各节点是否存活，并把结论同步到实例状态。
     *
     * <p>Redis 节点看「上一分钟是否采集到 INFO」；哨兵不在 INFO 采集范围内
     * （{@code collectRedisInfo} 显式跳过），只能单独 PING。</p>
     *
     * @return instanceId -> 是否存活；未参与探测或探测超时的节点不在 map 中
     */
    private Map<Integer, Boolean> refreshAliveStates(List<InstanceInfo> allInstanceInfoList,
                                                  Map<String, StandardStats> standardStatMap) {
        List<InstanceInfo> sentinelList = new ArrayList<>();
        List<InstanceInfo> redisList = new ArrayList<>();
        for (InstanceInfo instanceInfo : allInstanceInfoList) {
            if (!isAliveCheckTarget(instanceInfo)) {
                continue;
            }
            if (TypeUtil.isRedisSentinel(instanceInfo.getType())) {
                sentinelList.add(instanceInfo);
            } else {
                redisList.add(instanceInfo);
            }
        }

        Map<Integer, Boolean> aliveMap = new HashMap<>(probeSentinels(sentinelList));
        for (InstanceInfo instanceInfo : redisList) {
            aliveMap.put(instanceInfo.getId(), standardStatMap.containsKey(instanceInfo.getHostPort()));
        }
        for (InstanceInfo instanceInfo : allInstanceInfoList) {
            Boolean alive = aliveMap.get(instanceInfo.getId());
            if (alive != null) {
                instanceAliveStateService.refreshAliveState(instanceInfo, alive);
            }
        }
        return aliveMap;
    }

    /**
     * 并发 PING 全部哨兵。
     *
     * <p>单个哨兵不可达时 {@code isSentinelRun} 会重试 3 次、每次约 2 秒，串行探测会在哨兵
     * 大面积不可达时把分钟作业拖过一个调度周期，因此并发执行并设总超时。探测未在超时内
     * 返回的节点不写入结果——「无法判定」比误报一次存活告警更安全。</p>
     */
    private Map<Integer, Boolean> probeSentinels(List<InstanceInfo> sentinelList) {
        Map<Integer, Boolean> result = new ConcurrentHashMap<>();
        if (CollectionUtils.isEmpty(sentinelList)) {
            return result;
        }
        CountDownLatch latch = new CountDownLatch(sentinelList.size());
        for (InstanceInfo instanceInfo : sentinelList) {
            AsyncThreadPoolFactory.DEFAULT_ASYNC_THREAD_POOL.execute(() -> {
                try {
                    result.put(instanceInfo.getId(), redisCenter.isSentinelRun(
                            instanceInfo.getAppId(), instanceInfo.getIp(), instanceInfo.getPort()));
                } catch (Exception e) {
                    // 探测本身出错（例如集群信息缺失）不等于哨兵已死，跳过而不是判定为异常
                    logger.warn("probe sentinel {} failed: {}", instanceInfo.getHostPort(), e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            if (!latch.await(SENTINEL_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("sentinel alive probe timeout, finished {}/{}", result.size(), sentinelList.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("sentinel alive probe interrupted");
        }
        return result;
    }

    private boolean isAliveCheckTarget(InstanceInfo instanceInfo) {
        if (instanceInfo == null || instanceInfo.isOffline()) {
            return false;
        }
        return TypeUtil.isRedisType(instanceInfo.getType());
    }

    /**
     * 存活探测报警：按配置的比较关系判定，配置项形如「instance_alive 不等于 ok」。
     */
    private List<InstanceAlertValueResult> checkInstanceAlive(List<InstanceAlertConfig> aliveConfigList,
                                                              List<InstanceInfo> allInstanceInfoList,
                                                              Map<Integer, Boolean> aliveMap,
                                                              Date currentTime) {
        List<InstanceAlertValueResult> results = new ArrayList<>();
        if (CollectionUtils.isEmpty(aliveConfigList)) {
            return results;
        }
        Map<Integer, InstanceInfo> instanceById = new HashMap<>();
        for (InstanceInfo instanceInfo : allInstanceInfoList) {
            instanceById.put(instanceInfo.getId(), instanceInfo);
        }
        for (InstanceAlertConfig config : aliveConfigList) {
            if (!checkInCycle(config)) {
                continue;
            }
            for (InstanceInfo instanceInfo : resolveAliveTargets(config, allInstanceInfoList, instanceById)) {
                Boolean alive = aliveMap.get(instanceInfo.getId());
                if (alive == null) {
                    continue;
                }
                String currentValue = alive ? ALIVE_OK : ALIVE_FAIL;
                if (!AlertCompareUtil.isTriggered(config, currentValue)) {
                    continue;
                }
                results.add(new InstanceAlertValueResult(config, instanceInfo, currentValue,
                        instanceInfo.getAppId(), ""));
            }
            updateLastCheckTime(config.getId(), currentTime);
        }
        return results;
    }

    /** 全局配置作用于全部节点，实例配置只作用于指定节点 */
    private List<InstanceInfo> resolveAliveTargets(InstanceAlertConfig config,
                                                   List<InstanceInfo> allInstanceInfoList,
                                                   Map<Integer, InstanceInfo> instanceById) {
        if (!config.isSpecail()) {
            return allInstanceInfoList;
        }
        InstanceInfo target = config.getInstanceInfo() != null
                ? config.getInstanceInfo() : instanceById.get((int) config.getInstanceId());
        return target == null ? Collections.<InstanceInfo>emptyList() : Collections.singletonList(target);
    }

    /**
     * 发送邮件
     *
     * @param instanceAlertValueResultList
     */
    private void sendInstanceAlertEmail(Date beginTime, Date endTime,
                                        List<InstanceAlertValueResult> instanceAlertValueResultList) {
        if (CollectionUtils.isEmpty(instanceAlertValueResultList)) {
            return;
        }
        Collections.sort(instanceAlertValueResultList, new Comparator<InstanceAlertValueResult>() {

            @Override
            public int compare(InstanceAlertValueResult o1, InstanceAlertValueResult o2) {
                return (int) (o1.getAppId() - o2.getAppId());
            }
        });

        // 1.客户端应用定制报警
        Map<Long, List<InstanceAlertValueResult>> appAlertMap = new HashMap<Long, List<InstanceAlertValueResult>>();
        Set<Long> appSets = new HashSet<Long>();

        Map<Long, AppDesc> appDescMap = new HashMap<Long, AppDesc>();
        // 2.遍历报警实例
        for (InstanceAlertValueResult instanceAlertValueResult : instanceAlertValueResultList) {
            long appId = instanceAlertValueResult.getAppId();
            AppDesc appDesc = null;
            if (appDescMap.containsKey(appId)) {
                appDesc = appDescMap.get(appId);
            } else {
                appDesc = appDao.getAppDescById(instanceAlertValueResult.getAppId());
                if (appDesc != null) {
                    appDesc.setOfficer(userService.getOfficerName(appDesc.getOfficer()));
                }
                appDescMap.put(appId, appDesc);
            }
            // 实例可能挂在已删除的应用下，缺集群信息不应中断整批报警
            if (appDesc == null) {
                logger.warn("appDesc not found for appId={}, skip app-level alert grouping", appId);
                continue;
            }
            instanceAlertValueResult.setAppDesc(appDesc);
            // 找出定制报警的客户端
            if (appDesc.getIsAccessMonitor() == 1) {
                appSets.add(appId);
            }
        }
        // 3.按客户端定制报警应用分组发送
        for (Long appid : appSets) {
            List<InstanceAlertValueResult> instanceAlertList = new ArrayList<InstanceAlertValueResult>();
            for (InstanceAlertValueResult instanceAlert : instanceAlertValueResultList) {
                if (appid == instanceAlert.getAppId()) {
                    instanceAlertList.add(instanceAlert);
                }
            }
            if (instanceAlertList.size() > 0) {
                appAlertMap.put(appid, instanceAlertList);
                logger.warn("monitor alert appid:{},instance size ={}", appid, instanceAlertList.size());
            }
        }
        // 4.发送给管理员报警
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        int importantLevel = this.getAlertImportantLevel(instanceAlertValueResultList);
        String emailTitle = String.format("Redis实例分钟报警(%s~%s)", sdf.format(beginTime), sdf.format(endTime));
        if (importantLevel == ImportantLevelTypeEnum.URGENT.getType()
                || importantLevel == ImportantLevelTypeEnum.IMPORTANT.getType()
                || importantLevel == ImportantLevelTypeEnum.NORMAL.getType()) {
            emailTitle = String.format("[%s]Redis实例分钟报警(%s~%s)", ImportantLevelTypeEnum.getInfoByType(importantLevel), sdf.format(beginTime), sdf.format(endTime));
        }
        Map<String, Object> context = new HashMap<>();
        context.put("instanceAlertValueResultList", instanceAlertValueResultList);
        String emailContent = FreemakerUtils.createText("instanceAlert.ftl", configuration, context);
        appAlertRecordService.saveAlertInfoByType(AlertTypeEnum.INSTANCE_MINUTE_MONITOR, emailTitle, null, instanceAlertValueResultList);
        emailComponent.sendMailToAdmin(emailTitle, emailContent.replaceAll("\t", ""));

        // 5.发送给客户端定制报警
        for (Map.Entry<Long, List<InstanceAlertValueResult>> appAlert : appAlertMap.entrySet()) {
            Long appId = appAlert.getKey();
            List<InstanceAlertValueResult> instanceAlertList = appAlert.getValue();

            emailTitle = String.format("应用Redis分钟报警(%s~%s)", sdf.format(beginTime), sdf.format(endTime));
            Map<String, Object> context1 = new HashMap<>();
            context1.put("instanceAlertValueResultList", instanceAlertList);
            emailContent = FreemakerUtils.createText("appAlert.ftl", configuration, context1);
            // 获取报警用户列表
            List<AppUser> appUsers = userService.getAlertByAppId(appId);
            List<String> emailList = getEmailList(appUsers);
            if (emailList != null && emailList.size() > 0) {
                emailComponent.sendMail(emailTitle, emailContent, emailList);
            }
        }

    }

    /**
     * 根据报警结果，获取报警重要程度
     *
     * @param instanceAlertValueResultList
     * @return
     */
    private int getAlertImportantLevel(List<InstanceAlertValueResult> instanceAlertValueResultList) {
        int importantLevel = ImportantLevelTypeEnum.NORMAL.getType();
        for (InstanceAlertValueResult alertValueResult : instanceAlertValueResultList) {
            if (alertValueResult.getInstanceAlertConfig() != null && alertValueResult.getInstanceAlertConfig().getImportantLevel() != null) {
                if (importantLevel < alertValueResult.getInstanceAlertConfig().getImportantLevel()) {
                    importantLevel = alertValueResult.getInstanceAlertConfig().getImportantLevel();
                }
            }
        }
        return importantLevel;
    }

    /**
     * <p>
     * Description: 获取邮件列表
     * </p>
     *
     * @param
     * @return
     * @author chenshi
     * @version 1.0
     * @date 2017/9/25
     */
    public List<String> getEmailList(List<AppUser> appUsers) {
        List<String> emailList = new ArrayList<String>();
        if (appUsers != null && appUsers.size() > 0) {
            for (AppUser appUser : appUsers) {
                String email = appUser.getEmail();
                if (StringUtils.isNotBlank(email)) {
                    emailList.add(appUser.getEmail());
                }
            }
        }
        return emailList;
    }

    /**
     * 检测是否在周期内
     *
     * @param finalInstanceConfig
     * @return
     */
    private boolean checkInCycle(InstanceAlertConfig finalInstanceConfig) {
        if (EnvUtil.isLocal(environment)) {
            return true;
        }
        // 检测周期转换为毫秒
        long checkCycleMillionTime = finalInstanceConfig.getCheckCycleMillionTime();
        // 当前距离上一次检测过去的毫秒
        long betweenTime = System.currentTimeMillis() - finalInstanceConfig.getLastCheckTime().getTime();
        // 超过说明需要进行再测检测了
        if (betweenTime >= checkCycleMillionTime) {
            return true;
        }
        return false;
    }

    /**
     * 判断当前实例是否在特殊报警配置中
     *
     * @param specialInstanceAlertConfigList
     * @param instanceAlertConfig
     * @param instanceInfo
     * @return
     */
    private InstanceAlertConfig filterSpecial(List<InstanceAlertConfig> specialInstanceAlertConfigList, InstanceAlertConfig instanceAlertConfig, InstanceInfo instanceInfo) {
        // 如果没有则返回原来的配置
        if (CollectionUtils.isEmpty(specialInstanceAlertConfigList)) {
            return instanceAlertConfig;
        }
        // 寻找特殊配置 
        for (InstanceAlertConfig specialInstanceAlertConfig : specialInstanceAlertConfigList) {
            String specialAlertConfig = specialInstanceAlertConfig.getAlertConfig();
            long instanceId = specialInstanceAlertConfig.getInstanceId();
            // 配置名和实例id对上
            if (instanceAlertConfig.getAlertConfig().equals(specialAlertConfig) && instanceInfo.getId() == instanceId) {
                return specialInstanceAlertConfig;
            }
        }
        return instanceAlertConfig;
    }

    /**
     * 获取指定时间内的标准统计信息Map
     *
     * @param beginTime
     * @param endTime
     * @return
     */
    private Map<String, StandardStats> getStandardStatsMap(Date beginTime, Date endTime) {
        List<StandardStats> standardStatsList = standardStatsDao.getStandardStatsByCreateTime(beginTime, endTime, ConstUtils.REDIS);
        // 按照host:port做分组
        Map<String, StandardStats> resultMap = new HashMap<String, StandardStats>();
        for (StandardStats standardStats : standardStatsList) {
            String hostPort = standardStats.getIp() + ":" + standardStats.getPort();
            resultMap.put(hostPort, standardStats);
        }
        return resultMap;
    }

}
