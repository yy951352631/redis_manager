package com.shcj.cache.stats.app.impl;

import com.google.common.collect.Maps;
import com.shcj.cache.constant.AppTopology;
import com.shcj.cache.constant.TimeDimensionalityEnum;
import com.shcj.cache.dao.*;
import com.shcj.cache.entity.*;
import com.shcj.cache.machine.MachineCenter;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.task.constant.ResourceEnum;
import com.shcj.cache.util.AppClusterNoSupport;
import com.shcj.cache.util.CollectTimeUtil;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.PandectUtil;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.enums.BooleanEnum;
import com.shcj.cache.web.enums.MachineMemoryDistriEnum;
import com.shcj.cache.web.enums.StatEnum;
import com.shcj.cache.web.service.UserService;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.AppDetailVO;
import net.sf.json.JSONArray;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于app的统计信息的接口：包括app详情、app配置以及基于app的统计
 *
 * @author leifu
 * @Date 2015年3月2日
 * @Time 下午1:50:09
 */
@Service("appStatsCenter")
public class AppStatsCenterImpl implements AppStatsCenter {

    /** CPU 使用率的回溯窗口：采集是分钟级，15 分钟足以拿到首尾两条 */
    private static final long CPU_WINDOW_MILLIS = 15L * 60L * 1000L;

    private final static String COLLECT_DATE_FORMAT = "yyyyMMddHHmm";
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    @Autowired
    private AppDao appDao;
    @Autowired
    private InstanceDao instanceDao;
    @Autowired
    private MachineDao machineDao;
    @Autowired
    private AppStatsDao appStatsDao;

    @Autowired
    private com.shcj.cache.dao.InstanceRiskMetricDao instanceRiskMetricDao;
    @Autowired
    private InstanceLatencyHistoryDao instanceLatencyHistoryDao;
    @Autowired
    private InstanceSlowLogDao instanceSlowLogDao;
    @Autowired
    private InstanceStatsDao instanceStatsDao;
    @Autowired
    @Lazy
    private RedisCenter redisCenter;
    @Autowired
    private UserService userService;
    @Autowired
    @Lazy
    private MachineCenter machineCenter;
    @Autowired
    private ResourceDao resourceDao;

    @Override
    public List<AppStats> getAppStatsListByMinuteTime(long appId, long beginTime, long endTime) {
        Assert.isTrue(appId >= 0);
        Assert.isTrue(beginTime > 0 && endTime > 0);

        List<AppStats> appStatsList = null;
        try {
            // 应用统计页最长 7 天，统一查分钟表；小时表保留周期短，7 天区间经常缺数据
            appStatsList = appStatsDao.getAppStatsByMinute(appId, beginTime, endTime);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return appStatsList;
    }

    /**
     * 通过时间区间查询app的分钟统计数据
     *
     * @param appId
     * @param beginTime 时间，格式：yyyyMMddHHmm
     * @param endTime   时间，格式：yyyyMMddHHmm
     * @return
     */
    @Override
    public List<AppStats> getAppStatsList(final long appId, long beginTime, long endTime, TimeDimensionalityEnum timeDimensionalityEnum) {
        Assert.isTrue(appId >= 0);
        Assert.isTrue(beginTime > 0 && endTime > 0);

        List<AppStats> appStatsList = null;
        try {
            if (TimeDimensionalityEnum.MINUTE.equals(timeDimensionalityEnum)) {
                appStatsList = appStatsDao.getAppStatsByMinute(appId, beginTime, endTime);
            } else if (TimeDimensionalityEnum.HOUR.equals(timeDimensionalityEnum)) {
                long hourBegin = toHourCollectTime(beginTime);
                long hourEnd = toHourCollectTime(endTime);
                appStatsList = appStatsDao.getAppStatsByHour(appId, hourBegin, hourEnd);
                if (CollectionUtils.isEmpty(appStatsList)) {
                    appStatsList = appStatsDao.getAppStatsByMinute(appId, beginTime, endTime);
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return appStatsList;
    }

    /** 小时表 collect_time 为 yyyyMMddHH（10 位），需从分钟时间戳截断 */
    private static long toHourCollectTime(long minuteCollectTime) {
        return minuteCollectTime / 100;
    }

    @Override
    public List<AppCommandStats> getTop5AppCommandStatsList(final long appId, long begin, long end) {
        Assert.isTrue(appId >= 0);
        Assert.isTrue(begin > 0L);
        Assert.isTrue(end > 0L);

        List<AppCommandStats> topAppCmdList = null;
        try {
            topAppCmdList = appStatsDao.getTopAppCommandGroupSum(appId, new TimeDimensionality(begin, end, COLLECT_DATE_FORMAT), 5);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return topAppCmdList;
    }

    @Override
    public List<AppCommandStats> getTopLimitAppCommandStatsList(long appId, long begin, long end, int limit) {
        Assert.isTrue(appId >= 0);
        Assert.isTrue(begin > 0L);
        Assert.isTrue(end > 0L);

        List<AppCommandStats> topAppCmdList = null;
        try {
            topAppCmdList = appStatsDao.getTopAppCommandStatsList(appId, new TimeDimensionality(begin, end, COLLECT_DATE_FORMAT), limit);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return topAppCmdList;
    }

    /**
     * 查询应用的配置和节点信息
     *
     * @param appId
     * @return
     */
    @Override
    public Map<AppTopology, Object> queryAppTopology(final long appId) {
        Assert.isTrue(appId >= 0);

        Map<AppTopology, Object> appTopologyMap = new HashMap<AppTopology, Object>();
        AppDesc appDesc = null;
        double totalMemory = 0.0;
        Set<Long> machineSet = new HashSet<Long>();
        int masterCount = 0;
        int slaveCount = 0;

        List<InstanceInfo> instanceInfoList = null;
        try {
            appDesc = appDao.getAppDescById(appId);
            instanceInfoList = instanceDao.getInstListByAppId(appId);
            if (appDesc == null || instanceInfoList == null || instanceInfoList.isEmpty()) {
                logger.error("get app and it's instances error， appId = {}", appId);
                return null;
            }
            if (appDesc.getType() == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
                for (InstanceInfo instance : instanceInfoList) {
                    machineSet.add(instance.getHostId());
                    totalMemory += instance.getMem();
                    BooleanEnum isMaster = redisCenter.isMaster(appId, instance.getIp(), instance.getPort());
                    if (isMaster == BooleanEnum.OTHER) {
                        continue;
                    }
                    if (isMaster == BooleanEnum.TRUE) {
                        masterCount++;
                    } else {
                        slaveCount++;
                    }
                }
            }
            appTopologyMap.put(AppTopology.TOTAL_MEMORY, totalMemory / ConstUtils._1024);
            appTopologyMap.put(AppTopology.MACHINE_COUNT, machineSet.size());
            appTopologyMap.put(AppTopology.MASTER_COUNT, masterCount);
            appTopologyMap.put(AppTopology.SLAVE_COUNT, slaveCount);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

        return appTopologyMap;
    }

    /**
     * 查询应用指定时间段，指定命令名的结果集合
     *
     * @param appId       应用id
     * @param beginTime   时间，格式：yyyyMMddHHmm
     * @param endTime     时间，格式：yyyyMMddHHmm
     * @param commandName 命令名
     * @return
     */
    @Override
    public List<AppCommandStats> getCommandStatsList(long appId, long beginTime, long endTime, String commandName) {
        return appStatsDao.getAppCommandStatsList(appId, commandName, new TimeDimensionality(beginTime, endTime, COLLECT_DATE_FORMAT));
    }

    /**
     * 查询应用指定时间段，指定命令名的结果集合
     *
     * @param appId     应用id
     * @param beginTime 时间，格式：yyyyMMddHHmm
     * @param endTime   时间，格式：yyyyMMddHHmm
     * @return
     */
    @Override
    public List<AppCommandStats> getCommandStatsList(long appId, long beginTime, long endTime) {
        return appStatsDao.getAppAllCommandStatsList(appId, new TimeDimensionality(beginTime, endTime, COLLECT_DATE_FORMAT));
    }

    @Override
    public List<AppCommandStats> getCommandStatsListV2(long appId, long beginTime, long endTime, TimeDimensionalityEnum timeDimensionalityEnum, String commandName) {
        if (TimeDimensionalityEnum.MINUTE.equals(timeDimensionalityEnum)) {
            return appStatsDao.getAppCommandStatsListByMinuteWithCommand(appId, beginTime, endTime, commandName);
        } else if (TimeDimensionalityEnum.HOUR.equals(timeDimensionalityEnum)) {
            long hourBegin = toHourCollectTime(beginTime);
            long hourEnd = toHourCollectTime(endTime);
            List<AppCommandStats> list = appStatsDao.getAppCommandStatsListByHourWithCommand(
                    appId, hourBegin, hourEnd, commandName);
            if (CollectionUtils.isEmpty(list)) {
                return appStatsDao.getAppCommandStatsListByMinuteWithCommand(appId, beginTime, endTime, commandName);
            }
            return list;
        }
        return Collections.emptyList();
    }

    @Override
    public List<AppCommandStats> getCommandStatsListV2(long appId, long beginTime, long endTime, TimeDimensionalityEnum timeDimensionalityEnum) {
        if (TimeDimensionalityEnum.MINUTE.equals(timeDimensionalityEnum)) {
            return appStatsDao.getAppAllCommandStatsListByMinute(appId, beginTime, endTime);
        } else if (TimeDimensionalityEnum.HOUR.equals(timeDimensionalityEnum)) {
            long hourBegin = toHourCollectTime(beginTime);
            long hourEnd = toHourCollectTime(endTime);
            List<AppCommandStats> list = appStatsDao.getAppAllCommandStatsListByHour(appId, hourBegin, hourEnd);
            if (CollectionUtils.isEmpty(list)) {
                return appStatsDao.getAppAllCommandStatsListByMinute(appId, beginTime, endTime);
            }
            return list;
        }
        return Collections.emptyList();
    }


    /**
     * 查询应用指定命令的峰值
     *
     * @param appId       应用id
     * @param beginTime   时间，格式：yyyyMMddHHmm
     * @param endTime     时间，格式：yyyyMMddHHmm
     * @param commandName 命令名
     * @return
     */
    @Override
    public AppCommandStats getCommandClimax(long appId, Long beginTime, Long endTime, String commandName) {
        TimeDimensionality td = new TimeDimensionality(beginTime, endTime, COLLECT_DATE_FORMAT);
        AppCommandStats appCommandStats = appStatsDao.getCommandClimaxCount(appId, commandName, td);
        if (appCommandStats == null) {
            return null;
        }
        appCommandStats.setCommandName(commandName);
        AppCommandStats appCommandStatsTemp = appStatsDao.getCommandClimaxCreateTime(appId, commandName, appCommandStats.getCommandCount(), td);
        if (appCommandStatsTemp != null) {
            appCommandStats.setCreateTime(appCommandStatsTemp.getCreateTime());
        }
        return appCommandStats;
    }

    /**
     * 获取应用命令调用次数分布
     *
     * @param appId
     * @param beginTime
     * @param endTime
     * @return
     */
    @Override
    public List<AppCommandGroup> getAppCommandGroup(long appId, Long beginTime, Long endTime) {
        return appStatsDao.getAppCommandGroup(appId, new TimeDimensionality(beginTime, endTime, COLLECT_DATE_FORMAT));
    }

    /**
     * 获取应用详细信息
     */
    @Override
    public AppDetailVO getAppDetail(long appId) {
        AppDesc appDesc = null;
        if (AppClusterNoSupport.isClusterNo(appId)) {
            appDesc = appDao.getByClusterNo((int) appId);
        }
        if (appDesc == null) {
            appDesc = appDao.getAppDescById(appId);
        }
        if (appDesc == null) {
            return null;
        }
        appId = appDesc.getAppId();
        //1.获取Redis版本名称
        SystemResource redisResource = resourceDao.getResourceById(appDesc.getVersionId());
        if (redisResource != null) {
            appDesc.setVersionName(redisResource.getName());
            //2.判断版本是否可以升级
            List<SystemResource> versionList = resourceDao.getResourceList(ResourceEnum.REDIS.getValue());
            for (SystemResource version : versionList) {
                try {
                    // 大版本同一版本且大于当前版本号
                    int versionTag = Integer.parseInt(version.getName().replaceAll("redis-", "").replaceAll("\\.", ""));
                    int currentTag = Integer.parseInt(redisResource.getName().replaceAll("redis-", "").replaceAll("\\.", ""));
                    // 支持小版本号升级
                    String versionStr = redisResource.getName().substring(0, redisResource.getName().lastIndexOf("."));
                    if (version.getName().contains(versionStr) && versionTag > currentTag) {
                        appDesc.setIsVersionUpgrade(1);
                        break;
                    }
                } catch (Exception e) {
                    logger.error("parse version:{} {} exception : {}", redisResource.getName(), version.getName(), e.getMessage(), e);
                    appDesc.setIsVersionUpgrade(0);
                }
            }
        } else {
            appDesc.setIsVersionUpgrade(0);
        }

        AppDetailVO resultVO = new AppDetailVO();
        resultVO.setAppDesc(appDesc);
        fillOfficer(resultVO, appDesc.getOfficer());
        Set<String> machines = new HashSet<String>();
        List<InstanceInfo> instanceList = instanceDao.getInstListByAppId(appId);

        if (instanceList == null || instanceList.isEmpty()) {
            return resultVO;
        }
        long hits = 0L;
        long miss = 0L;
        double highestMemFragRatio = 0D;        //碎片率最大值
        long instId = 0L;           //碎片率最大值的实例id
        // 集群运行时长以在线实例中运行最久的那个为准
        long maxUptimeSeconds = 0L;
        List<InstanceStats> instanceStatsList = instanceStatsDao.getInstanceStatsByAppId(appId);
        if (instanceStatsList != null && instanceStatsList.size() > 0) {
            Map<Long, InstanceStats> instanceStatMap = new HashMap<>();
            for (InstanceStats stats : instanceStatsList) {
                instanceStatMap.put(stats.getInstId(), stats);
            }

            for (InstanceInfo instanceInfo : instanceList) {
                if (instanceInfo.isStop()) {
                    resultVO.addOffLineInstance(instanceInfo);
                    continue;
                }
                machines.add(instanceInfo.getIp());
                InstanceStats instanceStats = instanceStatMap.get((long) instanceInfo.getId());
                if (instanceStats == null) {
                    continue;
                }

                long uptimeSeconds = getUptimeSeconds(instanceStats);
                if (uptimeSeconds > 0) {
                    maxUptimeSeconds = Math.max(maxUptimeSeconds, uptimeSeconds);
                }
                boolean isMaster = isMaster(instanceStats);

                long usedMemoryMB = instanceStats.getUsedMemory() / 1024 / 1024;
                // 容量优先用采集的 maxmemory；未配置时用展示用有效上限，避免进度条分母为 0
                long maxMemoryBytes = instanceStats.getEffectiveMaxMemory();

                hits += instanceStats.getHits();
                miss += instanceStats.getMisses();
                //碎片率最大值
                double memFragRatio = instanceStats.getMemFragmentationRatio();
                if (memFragRatio > highestMemFragRatio) {
                    highestMemFragRatio = memFragRatio;
                    instId = instanceStats.getInstId();
                }
                // 内存详情按全部在线节点加总，与节点页/首页节点内存口径一致（不再只加 master）
                long maxMemMb = instanceStats.getMaxMemory() / (1024L * 1024L);
                if (maxMemMb <= 0 && instanceInfo.getMem() > 0) {
                    maxMemMb = instanceInfo.getMem();
                }
                if (maxMemMb <= 0) {
                    maxMemMb = maxMemoryBytes / (1024L * 1024L);
                }
                resultVO.setMem(resultVO.getMem() + maxMemMb);
                resultVO.setCurrentMem(resultVO.getCurrentMem() + usedMemoryMB);

                if (isMaster) {
                    resultVO.setCurrentObjNum(resultVO.getCurrentObjNum() + instanceStats.getCurrItems());
                    resultVO.setMasterNum(resultVO.getMasterNum() + 1);
                    //按instanceStats计算conn
                    resultVO.setConn(resultVO.getConn() + instanceStats.getCurrConnections());
                } else {
                    resultVO.setSlaveNum(resultVO.getSlaveNum() + 1);
                }
            }
        }

        resultVO.setCurrentKeyCount(resultVO.getCurrentObjNum());
        resultVO.setUptimeSeconds(maxUptimeSeconds);
        applyCpuAndQps(appId, resultVO);

        // 授权用户
        List<AppUser> userList = userService.getByAppId(appId);
        if (userList != null && userList.size() > 0) {
            resultVO.setAppUsers(userList);
        }
        // 报警用户
        List<AppUser> alertUsedrList = userService.getAlertByAppId(appId);
        if (alertUsedrList != null && alertUsedrList.size() > 0) {
            resultVO.setAlertUsers(alertUsedrList);
        }

        resultVO.setMachineNum(machines.size());
        // 使用率与列表展示的 mem/currentMem 同源，避免「全节点百分比 × master 容量」错位
        if (resultVO.getMem() <= 0L) {
            resultVO.setMemUsePercent(0.0D);
        } else {
            double percent = 100 * (double) resultVO.getCurrentMem() / (double) resultVO.getMem();
            DecimalFormat df = new DecimalFormat("##.##");
            resultVO.setMemUsePercent(Double.parseDouble(df.format(percent)));
        }
        //最大碎片率及对应实例Id
        resultVO.setHighestMemFragRatio(highestMemFragRatio);
        resultVO.setInstIdWithHighestMemFragRatio(instId);

        // 界面要把命中率公式带数字展示，原始分子分母一并给出去
        resultVO.setKeyspaceHits(hits);
        resultVO.setKeyspaceMisses(miss);

        if (miss == 0L) {
            if (hits > 0) {
                resultVO.setHitPercent(100.0D);
            } else {
                resultVO.setHitPercent(0.0D);
            }
        } else {
            double percent = 100 * (double) hits / (hits + miss);
            DecimalFormat df = new DecimalFormat("##.##");
            resultVO.setHitPercent(Double.parseDouble(df.format(percent)));
        }

        return resultVO;
    }

    /** INFO CPU 字段是累计秒数，使用相邻两次应用采集值计算当前使用率。 */
    /**
     * 集群 CPU 使用率：各数据节点使用率的平均值。
     *
     * <p>原实现有两处问题。其一，数据源 app_minute_statistics 的 cpu_sys/cpu_user 是
     * 整型秒，一个每分钟只用掉 0.13 CPU 秒的集群会被截断成 0；其二，间隔算的是
     * {@code (collectTime/100 - collectTime/100) * 60}，而 collect_time 是
     * yyyyMMddHHmm，除以 100 抹掉的是分钟位，同一小时内恒为 0 直接短路返回。
     * 两个问题叠加，这个值实际上一直是 0。
     *
     * <p>改用 instance_risk_metric_minute 的 double 累计值，按实例取窗口首尾两条相减，
     * 除以真实时间跨度。取平均而非求和：求和在多节点集群上会轻易超过 100%，与旁边
     * 那列「内存使用率」的口径也对不上。</p>
     */
    private void applyCpuAndQps(long appId, AppDetailVO resultVO) {
        long since = NumberUtils.toLong(DateUtil.formatDate(
                new Date(System.currentTimeMillis() - CPU_WINDOW_MILLIS), "yyyyMMddHHmm"), 0L);
        List<Map<String, Object>> rows;
        try {
            rows = instanceRiskMetricDao.cpuBoundaryByApp(appId, since);
        } catch (Exception e) {
            logger.warn("load cpu/ops boundary failed appId={}: {}", appId, e.getMessage());
            return;
        }
        if (CollectionUtils.isEmpty(rows)) {
            return;
        }
        resultVO.setQps(sumLatestOps(rows));
        // SQL 已按 (instance_id, collect_time) 排序，同一实例的两行紧挨着且首条在前
        Map<Long, List<Map<String, Object>>> byInstance = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long instanceId = NumberUtils.toLong(String.valueOf(row.get("instanceId")), 0L);
            byInstance.computeIfAbsent(instanceId, key -> new ArrayList<>()).add(row);
        }

        double totalPercent = 0.0D;
        int counted = 0;
        for (List<Map<String, Object>> samples : byInstance.values()) {
            if (samples.size() < 2) {
                continue;
            }
            Map<String, Object> first = samples.get(0);
            Map<String, Object> last = samples.get(samples.size() - 1);
            double delta = cpuSeconds(last) - cpuSeconds(first);
            long span = CollectTimeUtil.secondsBetween(
                    NumberUtils.toLong(String.valueOf(first.get("collectTime")), 0L),
                    NumberUtils.toLong(String.valueOf(last.get("collectTime")), 0L));
            // 跨度取不到或实例重启过（增量为负）就不计入均值，掺进去只会把结果压低
            if (span <= 0 || delta < 0) {
                continue;
            }
            totalPercent += CollectTimeUtil.cpuUsePercent(delta, span);
            counted++;
        }
        if (counted == 0) {
            return;
        }
        resultVO.setCpuUsePercent(Math.round(totalPercent / counted * 10.0D) / 10.0D);
    }

    /**
     * 集群 QPS：各数据节点最近一次采集的 instantaneous_ops_per_sec 之和。
     *
     * <p>这个字段是 Redis 给的瞬时速率而不是累计计数，取最新一条即可，不用作差。
     * 只累加最后一轮：入参里同时含有前一轮，混在一起会把 QPS 算成两倍。</p>
     */
    private long sumLatestOps(List<Map<String, Object>> rows) {
        long latestCollectTime = 0L;
        for (Map<String, Object> row : rows) {
            latestCollectTime = Math.max(latestCollectTime,
                    NumberUtils.toLong(String.valueOf(row.get("collectTime")), 0L));
        }
        long qps = 0L;
        for (Map<String, Object> row : rows) {
            if (NumberUtils.toLong(String.valueOf(row.get("collectTime")), 0L) != latestCollectTime) {
                continue;
            }
            qps += Math.max(0L, NumberUtils.toLong(String.valueOf(row.get("instantaneousOps")), 0L));
        }
        return qps;
    }

    private double cpuSeconds(Map<String, Object> row) {
        return NumberUtils.toDouble(String.valueOf(row.get("usedCpuSys")), 0D)
                + NumberUtils.toDouble(String.valueOf(row.get("usedCpuUser")), 0D);
    }

    @SuppressWarnings("unchecked")
    private long getUptimeSeconds(InstanceStats stats) {
        if (stats == null) {
            return 0L;
        }
        // 列表聚合走的 SQL 不带 info 明细，优先用采集时落库的 uptime_in_seconds
        if (stats.getUptimeInSeconds() > 0) {
            return stats.getUptimeInSeconds();
        }
        if (stats.getInfoMap() == null) {
            return 0L;
        }
        Object server = stats.getInfoMap().get("Server");
        if (!(server instanceof Map)) {
            server = stats.getInfoMap().get("server");
        }
        if (!(server instanceof Map)) {
            return 0L;
        }
        Object uptime = ((Map<String, Object>) server).get("uptime_in_seconds");
        if (uptime == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(uptime));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @Override
    public Map<Long, AppDetailVO> getOnlineAppDetails() {
        List<AppDesc> appDescList = appDao.getOnlineApps();
        Map<Long, AppDetailVO> appDetailVOMap = appDescList.stream()
                .map(appDesc -> getAppDetail(appDesc.getAppId()))
                .collect(Collectors.toMap(appDetail -> appDetail.getAppDesc().getAppId(), appDetail -> appDetail));
        return appDetailVOMap;
    }

    @Override
    public List<AppClientStatisticGather> getOnlineAppConnClients() {
        List<AppClientStatisticGather> result = new ArrayList<>();
        List<AppDesc> appDescList = appDao.getOnlineApps();
        appDescList.forEach(appDesc -> {
            AppClientStatisticGather gather = new AppClientStatisticGather();
            long appId = appDesc.getAppId();
            List<Map<String, Object>> addrInstanceList = redisCenter.getAppClientList(appId, 0);
            int totalConnectedClients = 0;
            for (Map<String, Object> addrInstance : addrInstanceList) {
                totalConnectedClients += MapUtils.getIntValue(addrInstance, "size", 0);
            }

            gather.setGatherTime(DateUtil.formatYYYY_MM_dd(new Date()));
            gather.setAppId(appId);
            gather.setConnectedClients(totalConnectedClients);
            result.add(gather);
        });
        return result;
    }

    private void fillOfficer(AppDetailVO resultVO, String officer) {
        long officerUserId = resolveOfficerUserId(officer);
        if (officerUserId > 0L) {
            resultVO.setOfficer(userService.get(officerUserId));
        }
    }

    private long resolveOfficerUserId(String officer) {
        if (StringUtils.isBlank(officer)) {
            return 0L;
        }
        String firstOfficer = officer.split(",")[0].trim();
        return NumberUtils.toLong(firstOfficer, 0L);
    }

    private boolean isMaster(InstanceStats instanceStats) {
        return instanceStats.getRole() == 1 ? true : false;
    }

    @Override
    public String executeCommand(long appId, String command, String userName) {
        if (StringUtils.isBlank(command)) {
            return "命令不能为空";
        }
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (appDesc == null) {
            return "app not found";
        }
        if (TypeUtil.isRedisType(appDesc.getType())) {
            return redisCenter.executeCommand(appDesc, command, userName);
        }
        return "not support app";
    }

    @Override
    public Map<String, Long> getInstanceSlowLogCountMapByAppId(Long appId, Date startDate, Date endDate) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (appDesc == null) {
            return Collections.emptyMap();
        }
        if (TypeUtil.isRedisType(appDesc.getType())) {
            return redisCenter.getInstanceSlowLogCountMapByAppId(appId, startDate, endDate);
        }
        return Collections.emptyMap();
    }

    @Override
    public List<InstanceSlowLog> getInstanceSlowLogByAppId(long appId, Date startDate, Date endDate) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (appDesc == null) {
            return Collections.emptyList();
        }
        if (TypeUtil.isRedisType(appDesc.getType())) {
            return redisCenter.getInstanceSlowLogByAppId(appId, startDate, endDate);
        }
        return Collections.emptyList();
    }

    @Override
    public Map<String, List<Map<String, Object>>> getAppLatencyStats(long appId, long startTime, long endTime) {
        try {
            List<Map<String, Object>> appLatencyInfoList = instanceLatencyHistoryDao.getAppLatencyStats(appId, startTime, endTime);
            Map<String, List<Map<String, Object>>> appLatencyInfoMap = Maps.newHashMap();

            appLatencyInfoList.stream().forEach(appLatencyInfo -> {
                String event = MapUtils.getString(appLatencyInfo, "event");
                ArrayList appEventLatency = (ArrayList) MapUtils.getObject(appLatencyInfoMap, event);
                if (CollectionUtils.isEmpty(appEventLatency)) {
                    appEventLatency = Lists.newArrayList();
                    appLatencyInfoMap.put(event, appEventLatency);
                }
                appEventLatency.add(appLatencyInfo);
            });
            return appLatencyInfoMap;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    @Override
    public Map<String, Long> getAppLatencyStatsGroupByInstance(long appId, long startTime, long endTime) {
        try {
            List<Map<String, Object>> appLatencyInfoList = instanceLatencyHistoryDao.getAppLatencyStatsGroupByInstance(appId, startTime, endTime);
            Map<String, Long> appInstanceLatencyStats = appLatencyInfoList.stream()
                    .collect(Collectors.toMap(latencyInfo -> MapUtils.getString(latencyInfo, "host_port"), latencyInfo -> MapUtils.getLong(latencyInfo, "count")));
            return appInstanceLatencyStats;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyMap();
        }
    }


    @Override
    public Map<String, List<Map<String, Object>>> getAppLatencyInfo(long appId, long startTime, long endTime) {
        try {
            List<Map<String, Object>> appLatencyInfoList = instanceLatencyHistoryDao.getAppLatencyInfo(appId, startTime, endTime, "");
            Map<String, List<Map<String, Object>>> appLatencyInfoMap = Maps.newHashMap();

            appLatencyInfoList.stream().forEach(appLatencyInfo -> {
                String host_port = MapUtils.getString(appLatencyInfo, "host_port");
                ArrayList appEventLatency = (ArrayList) MapUtils.getObject(appLatencyInfoMap, host_port);
                if (CollectionUtils.isEmpty(appEventLatency)) {
                    appEventLatency = Lists.newArrayList();
                    appLatencyInfoMap.put(host_port, appEventLatency);
                }
                appEventLatency.add(appLatencyInfo);
            });
            return appLatencyInfoMap;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    @Override
    public List<InstanceSlowLog> getByInstanceExecuteTime(long instanceId, String executeDate) {
        try {
            return instanceSlowLogDao.getByInstanceExecuteTime(instanceId, executeDate);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, Object> getAppTotalStat() {

        Map<String, Object> totalMap = new HashMap<String, Object>();

        /**
         * 1.从mysql获取
         * 2.从jvm获取
         */
        if (PandectUtil.getFromMysql() || MapUtils.isEmpty(PandectUtil.getPandectMap())) {
            // 1.基础统计（与列表口径对齐：在线集群 status=2；节点 status in 0,1）
            List<AppDesc> onlineApps = appDao.getOnlineApps();
            totalMap.put(StatEnum.TOTAL_EFFETIVE_APP.value(), onlineApps == null ? 0 : onlineApps.size());
            List<InstanceInfo> allInsts = instanceDao.getAllInsts();
            totalMap.put(StatEnum.TOTAL_INSTANCE_NUM.value(), allInsts == null ? 0 : allInsts.size());
            // 机器数：已登记 machine_info + 未登记但仍有实例的 IP（外部纳管）
            Set<String> machineIps = new HashSet<String>();
            List<MachineInfo> machineInfoList = machineDao.getAllMachines();
            if (machineInfoList != null) {
                for (MachineInfo machineInfo : machineInfoList) {
                    if (machineInfo != null && StringUtils.isNotBlank(machineInfo.getIp())) {
                        machineIps.add(machineInfo.getIp());
                    }
                }
            }
            if (allInsts != null) {
                for (InstanceInfo instanceInfo : allInsts) {
                    if (instanceInfo != null && StringUtils.isNotBlank(instanceInfo.getIp())) {
                        machineIps.add(instanceInfo.getIp());
                    }
                }
            }
            totalMap.put(StatEnum.TOTAL_MACHINE_NUM.value(), machineIps.size());

            // Redis 版本由纳管实例探测后写入 app_desc，不再依赖 system_resource/version_id。
            Map<String, Integer> detectedVersionCounts = new TreeMap<String, Integer>();
            if (onlineApps != null) {
                for (AppDesc app : onlineApps) {
                    String versionName = StringUtils.trimToEmpty(app.getVersionName());
                    if (StringUtils.isBlank(versionName) || "-".equals(versionName)) {
                        continue;
                    }
                    detectedVersionCounts.put(versionName,
                            detectedVersionCounts.getOrDefault(versionName, 0) + 1);
                }
            }
            List<ParamCount> redisDistribute = new ArrayList<ParamCount>();
            for (Map.Entry<String, Integer> entry : detectedVersionCounts.entrySet()) {
                redisDistribute.add(new ParamCount(entry.getKey(), entry.getValue(), ""));
            }
            totalMap.put(StatEnum.REDIS_VERSION_DISTRIBUTE.value(), JSONArray.fromObject(redisDistribute));
            totalMap.put(StatEnum.REDIS_VERSION_COUNT.value(), detectedVersionCounts.size());

            // 4.获取机器内存分配/机器内存使用分布
            // 4.1 机器内存使用分布
            Map<MachineMemoryDistriEnum, Integer> machineMemoryDistributeMap = machineCenter.getUsedMemoryDistribute();
            List<ParamCount> machineMemoryDistributeList = new ArrayList<ParamCount>();
            for (Map.Entry<MachineMemoryDistriEnum, Integer> entry : machineMemoryDistributeMap.entrySet()) {
                ParamCount paramCount = new ParamCount(entry.getKey().getInfo(), entry.getValue(), "");
                machineMemoryDistributeList.add(paramCount);
            }
            totalMap.put(StatEnum.MACHINE_USEDMEMORY_DISTRIBUTE.value(), JSONArray.fromObject(machineMemoryDistributeList));
            // 4.2 机器内存分配分布
            Map<MachineMemoryDistriEnum, Integer> maxMemoryDistributeMap = machineCenter.getMaxMemoryDistribute();
            List<ParamCount> maxMemoryDistributeList = new ArrayList<ParamCount>();
            for (Map.Entry<MachineMemoryDistriEnum, Integer> entry : maxMemoryDistributeMap.entrySet()) {
                ParamCount paramCount = new ParamCount(entry.getKey().getInfo(), entry.getValue(), "");
                maxMemoryDistributeList.add(paramCount);
            }
            totalMap.put(StatEnum.MACHINE_MAXMEMORY_DISTRIBUTE.value(), JSONArray.fromObject(maxMemoryDistributeList));
            // 4.3 机房分布
            List<Map<String, Object>> roomStat = machineDao.getRoomStat();
            List<ParamCount> roomDistribute = new ArrayList<ParamCount>();
            if (roomStat != null && roomStat.size() > 0) {
                for (Map<String, Object> room : roomStat) {
                    ParamCount paramCount = new ParamCount(MapUtils.getString(room, "name"), MapUtils.getInteger(room, "num"), "");
                    roomDistribute.add(paramCount);
                }
            }
            totalMap.put(StatEnum.MACHIEN_ROOM_DISTRIBUTE.value(), JSONArray.fromObject(roomDistribute));

            // 5.最后一次从数据库获取时间
            totalMap.put(PandectUtil.KEY_LASTTIME, System.currentTimeMillis());
            // 6.暂存到jvm
            PandectUtil.setPandectMap(totalMap);
        } else {
            totalMap.putAll(PandectUtil.getPandectMap());
        }
        return totalMap;
    }

}
