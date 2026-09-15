package com.shcj.cache.web.service;

import com.shcj.cache.constant.AppStatusEnum;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.dao.QuartzDao;
import com.shcj.cache.dao.TaskQueueDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.InstanceSlotModel;
import com.shcj.cache.entity.MachineStats;
import com.shcj.cache.entity.ParamCount;
import com.shcj.cache.machine.MachineCenter;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.app.ExternalRedisCenter;
import com.shcj.cache.task.constant.TaskQueueEnum.TaskStatusEnum;
import com.shcj.cache.util.AppClusterNoSupport;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.enums.StatEnum;
import com.shcj.cache.web.enums.TriggerStateEnum;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.AppDetailVO;
import com.shcj.cache.web.vo.MachineStatsVo;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 全局统计大盘（供 SPA DashboardApiController 使用）
 */
@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);

    private static final long DETAILS_CACHE_MS = 30_000L;
    private static final long CLUSTER_CHECK_TIMEOUT_SEC = 5L;
    private static final int CLUSTER_CHECK_POOL_SIZE = Math.min(8, Runtime.getRuntime().availableProcessors() * 2);

    @Resource(name = "appStatsCenter")
    private AppStatsCenter appStatsCenter;

    @Resource
    private MachineCenter machineCenter;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private TaskQueueDao taskQueueDao;

    @Autowired
    private QuartzDao quartzDao;

    @Resource
    private AppDao appDao;

    @Resource(name = "externalRedisCenter")
    private ExternalRedisCenter externalRedisCenter;

    @Resource
    private InstanceDao instanceDao;

    @Resource
    private RedisCenter redisCenter;

    @Autowired
    private AppService appService;

    private final ExecutorService clusterCheckPool = Executors.newFixedThreadPool(CLUSTER_CHECK_POOL_SIZE);

    private final ExecutorService detailsPool = Executors.newFixedThreadPool(2);

    private volatile DetailsCache detailsCache;

    /** 首屏轻量数据（统计卡片、内存、调度、任务） */
    public DashboardOverviewBundleDto buildOverviewBundle() {
        List<MachineStatsVo> machineStatsVoList = machineCenter.getmachineStatsVoList();
        DashboardOverviewBundleDto bundle = new DashboardOverviewBundleDto();
        bundle.setOverview(buildOverview(machineStatsVoList));
        bundle.setMachineStats(buildMachineStatsList(machineStatsVoList));
        bundle.setQuartz(buildQuartzStats());
        bundle.setTasks(buildTaskStats());
        return bundle;
    }

    /** 详情数据（异常总览 + 图表），带 30s 内存缓存 */
    public DashboardDetailsDto buildDetailsBundle() {
        long now = System.currentTimeMillis();
        DetailsCache cache = detailsCache;
        if (cache != null && cache.expireAt > now) {
            return cache.data;
        }
        synchronized (this) {
            cache = detailsCache;
            if (cache != null && cache.expireAt > System.currentTimeMillis()) {
                return cache.data;
            }
            DashboardDetailsDto details = buildDetailsBundleFresh();
            detailsCache = new DetailsCache(System.currentTimeMillis() + DETAILS_CACHE_MS, details);
            return details;
        }
    }

    /** 完整大盘（兼容旧接口，内部并行组装） */
    public DashboardDto buildDashboard() {
        CompletableFuture<DashboardOverviewBundleDto> overviewFuture =
                CompletableFuture.supplyAsync(this::buildOverviewBundle, detailsPool);
        CompletableFuture<DashboardDetailsDto> detailsFuture =
                CompletableFuture.supplyAsync(this::buildDetailsBundle, detailsPool);
        try {
            DashboardOverviewBundleDto overviewBundle = overviewFuture.get(60, TimeUnit.SECONDS);
            DashboardDetailsDto details = detailsFuture.get(60, TimeUnit.SECONDS);
            DashboardDto dto = new DashboardDto();
            dto.setOverview(overviewBundle.getOverview());
            dto.setMachineStats(overviewBundle.getMachineStats());
            dto.setQuartz(overviewBundle.getQuartz());
            dto.setTasks(overviewBundle.getTasks());
            dto.setAbnormalOverview(details.getAbnormalOverview());
            dto.setCharts(details.getCharts());
            return dto;
        } catch (Exception e) {
            logger.error("buildDashboard error", e);
            throw new IllegalStateException("加载全局统计失败", e);
        }
    }

    private DashboardDetailsDto buildDetailsBundleFresh() {
        DashboardContext ctx = loadDetailsContext();
        CompletableFuture<DashboardAbnormalOverviewDto> abnormalFuture =
                CompletableFuture.supplyAsync(() -> buildAbnormalOverview(ctx), detailsPool);
        CompletableFuture<DashboardChartsDto> chartsFuture =
                CompletableFuture.supplyAsync(() -> buildCharts(ctx), detailsPool);
        try {
            CompletableFuture.allOf(abnormalFuture, chartsFuture).get(90, TimeUnit.SECONDS);
            DashboardDetailsDto details = new DashboardDetailsDto();
            details.setAbnormalOverview(abnormalFuture.get());
            details.setCharts(chartsFuture.get());
            return details;
        } catch (Exception e) {
            logger.error("buildDetailsBundleFresh error", e);
            throw new IllegalStateException("加载全局统计详情失败", e);
        }
    }

    private DashboardContext loadDetailsContext() {
        DashboardContext ctx = new DashboardContext();
        ctx.topologySearchDate = DateUtil.formatDate(DateUtils.addDays(new Date(), -1), "yyyy-MM-dd");
        ctx.onlineApps = appDao.getOnlineApps();
        if (ctx.onlineApps == null) {
            ctx.onlineApps = Collections.emptyList();
        }
        ctx.onlineAppDetails = appStatsCenter.getOnlineAppDetails();
        ctx.appClientGatherMap = appService.getAppClientStatGather(-1L, ctx.topologySearchDate);
        ctx.appClientGatherStatGroup = appService.getFilterAppClientStatGather(-1L, ctx.topologySearchDate);
        return ctx;
    }

    private DashboardOverviewDto buildOverview(List<MachineStatsVo> machineStatsVoList) {
        Map<String, Object> appTotalStat = appStatsCenter.getAppTotalStat();
        DashboardOverviewDto overview = new DashboardOverviewDto();
        overview.setTotalRunningApps(MapUtils.getString(appTotalStat, StatEnum.TOTAL_EFFETIVE_APP.value(), "0"));
        overview.setTotalMachineCount(MapUtils.getString(appTotalStat, StatEnum.TOTAL_MACHINE_NUM.value(), "0"));
        overview.setTotalRunningInstance(MapUtils.getString(appTotalStat, StatEnum.TOTAL_INSTANCE_NUM.value(), "0"));
        Map<String, Integer> versionCounts = new TreeMap<String, Integer>();
        List<AppDesc> onlineApps = appDao.getOnlineApps();
        if (onlineApps != null) {
            for (AppDesc app : onlineApps) {
                if (StringUtils.isNotBlank(app.getName())) {
                    overview.getOnlineClusterNames().add(app.getName());
                }
                String version = StringUtils.trimToEmpty(app.getVersionName());
                if (StringUtils.isBlank(version) || "-".equals(version)) continue;
                versionCounts.put(version, versionCounts.getOrDefault(version, 0) + 1);
            }
        }
        for (Map.Entry<String, Integer> entry : versionCounts.entrySet()) {
            overview.getRedisVersions().add(new ChartItemDto(entry.getKey(), entry.getValue()));
        }
        overview.setRedisTypeCount(String.valueOf(versionCounts.size()));

        if (onlineApps != null) {
            Set<Long> onlineAppIds = onlineApps.stream().map(AppDesc::getAppId).collect(Collectors.toSet());
            Set<String> machineIps = new TreeSet<String>();
            List<com.shcj.cache.entity.InstanceInfo> instances = instanceDao.getAllInsts();
            if (instances != null) {
                for (com.shcj.cache.entity.InstanceInfo instance : instances) {
                    if (instance != null && instance.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus()
                            && onlineAppIds.contains(instance.getAppId())
                            && StringUtils.isNotBlank(instance.getIp())) {
                        machineIps.add(instance.getIp());
                    }
                }
            }
            overview.getOnlineMachineIps().addAll(machineIps);
        }

        if (machineStatsVoList != null && !machineStatsVoList.isEmpty()) {
            // machineStatsVoList[0] 已是汇总行 machineRoom=total，勿再对各机房加总（会翻倍）
            MachineStatsVo ms = resolveTotalMachineStats(machineStatsVoList);
            overview.setMachineMemUsedGb(round2((ms.getTotalMachineMem() - ms.getTotalMachineFreeMem()) / 1024.0));
            overview.setMachineMemTotalGb(round2(ms.getTotalMachineMem() / 1024.0));
            overview.setMachineMemUsedRatio(ms.getTotalMachineMem() > 0 ? round1(ms.getMachineMemUsedRatio()) : 0);
            overview.setInstanceMemUsedGb(round2(ms.getTotalInstanceUsedMem() / 1024.0 / 1024.0 / 1024.0));
            overview.setInstanceMemTotalGb(round2(ms.getTotalInstanceMaxMem() / 1024.0 / 1024.0 / 1024.0));
            overview.setInstanceMemUsedRatio(ms.getTotalInstanceMaxMem() > 0 ? round1(ms.getInstanceMemUsedRatio()) : 0);
        }
        return overview;
    }

    private MachineStatsVo resolveTotalMachineStats(List<MachineStatsVo> list) {
        for (MachineStatsVo vo : list) {
            if (vo != null && "total".equalsIgnoreCase(vo.getMachineRoom())) {
                return vo;
            }
        }
        MachineStatsVo total = new MachineStatsVo();
        for (MachineStatsVo vo : list) {
            if (vo == null) {
                continue;
            }
            total.setTotalMachineMem(total.getTotalMachineMem() + vo.getTotalMachineMem());
            total.setTotalMachineFreeMem(total.getTotalMachineFreeMem() + vo.getTotalMachineFreeMem());
            total.setTotalInstanceMaxMem(total.getTotalInstanceMaxMem() + vo.getTotalInstanceMaxMem());
            total.setTotalInstanceUsedMem(total.getTotalInstanceUsedMem() + vo.getTotalInstanceUsedMem());
        }
        return total;
    }

    private List<DashboardMachineStatsDto> buildMachineStatsList(List<MachineStatsVo> machineStatsVoList) {
        List<DashboardMachineStatsDto> result = new ArrayList<>();
        if (machineStatsVoList == null) {
            return result;
        }
        for (MachineStatsVo vo : machineStatsVoList) {
            if (vo == null) {
                continue;
            }
            DashboardMachineStatsDto item = new DashboardMachineStatsDto();
            item.setMachineRoom(vo.getMachineRoom());
            item.setMachineMemUsedGb(round2((vo.getTotalMachineMem() - vo.getTotalMachineFreeMem()) / 1024.0));
            item.setMachineMemTotalGb(round2(vo.getTotalMachineMem() / 1024.0));
            item.setMachineMemUsedRatio(round1(vo.getMachineMemUsedRatio()));
            item.setInstanceMemUsedGb(round2(vo.getTotalInstanceUsedMem() / 1024.0 / 1024.0 / 1024.0));
            item.setInstanceMemTotalGb(round2(vo.getTotalInstanceMaxMem() / 1024.0 / 1024.0 / 1024.0));
            item.setInstanceMemUsedRatio(round1(vo.getInstanceMemUsedRatio()));
            result.add(item);
        }
        return result;
    }

    private DashboardQuartzStatsDto buildQuartzStats() {
        int triggerWaitingCount = quartzDao.getTriggerStateCount(TriggerStateEnum.WAITING.getState());
        int triggerErrorCount = quartzDao.getTriggerStateCount(TriggerStateEnum.ERROR.getState());
        int triggerPausedCount = quartzDao.getTriggerStateCount(TriggerStateEnum.PAUSED.getState());
        int triggerAcquiredCount = quartzDao.getTriggerStateCount(TriggerStateEnum.ACQUIRED.getState());
        int triggerBlockedCount = quartzDao.getTriggerStateCount(TriggerStateEnum.BLOCKED.getState());
        int misfireCount = quartzDao.getMisFireTriggerCount();

        DashboardQuartzStatsDto quartz = new DashboardQuartzStatsDto();
        quartz.setTriggerWaitingCount(triggerWaitingCount);
        quartz.setTriggerErrorCount(triggerErrorCount);
        quartz.setTriggerPausedCount(triggerPausedCount);
        quartz.setTriggerAcquiredCount(triggerAcquiredCount);
        quartz.setTriggerBlockedCount(triggerBlockedCount);
        quartz.setMisfireCount(misfireCount);
        quartz.setTriggerTotalCount(triggerWaitingCount + triggerErrorCount + triggerPausedCount
                + triggerAcquiredCount + triggerBlockedCount);
        return quartz;
    }

    private DashboardTaskStatsDto buildTaskStats() {
        int newTaskCount = taskQueueDao.getStatusCount(TaskStatusEnum.NEW.getStatus());
        int runningTaskCount = taskQueueDao.getStatusCount(TaskStatusEnum.RUNNING.getStatus());
        int abortTaskCount = taskQueueDao.getStatusCount(TaskStatusEnum.ABORT.getStatus());
        int successTaskCount = taskQueueDao.getStatusCount(TaskStatusEnum.SUCCESS.getStatus());

        DashboardTaskStatsDto tasks = new DashboardTaskStatsDto();
        tasks.setNewTaskCount(newTaskCount);
        tasks.setRunningTaskCount(runningTaskCount);
        tasks.setAbortTaskCount(abortTaskCount);
        tasks.setSuccessTaskCount(successTaskCount);
        tasks.setTotalTaskCount(newTaskCount + runningTaskCount + abortTaskCount + successTaskCount);
        return tasks;
    }

    private DashboardAbnormalOverviewDto buildAbnormalOverview(DashboardContext ctx) {
        List<Map<String, Object>> topologyAppStats = ctx.appClientGatherStatGroup.get("topologyAppStats");
        if (topologyAppStats == null) {
            topologyAppStats = Collections.emptyList();
        }

        List<Map<String, Object>> slotAbnormalApps = listClusterSlotAbnormalApps(ctx.onlineApps);
        List<Map<String, Object>> instanceAbnormalApps = listInstanceAbnormalApps(ctx.onlineApps);
        List<Map<String, Object>> probeAbnormalApps = listExternalAbnormalApps(ctx.onlineApps);
        List<Map<String, Object>> unknownStatusApps = listUnknownStatusApps(ctx.onlineApps);
        List<Map<String, Object>> abnormalOverviewApps =
                mergeAbnormalOverviewApps(topologyAppStats, slotAbnormalApps, instanceAbnormalApps,
                        probeAbnormalApps, unknownStatusApps);

        List<Map<String, Object>> preview = abnormalOverviewApps;
        if (abnormalOverviewApps.size() > 20) {
            preview = abnormalOverviewApps.subList(0, 20);
        }

        Map<Long, AppDesc> abnormalAppDescMap = new HashMap<>();
        enrichAbnormalAppDescMap(abnormalAppDescMap, preview);

        DashboardAbnormalOverviewDto abnormal = new DashboardAbnormalOverviewDto();
        abnormal.setTopologySearchDate(ctx.topologySearchDate);
        abnormal.setTopologyAbnormalTotal(topologyAppStats.size());
        abnormal.setSlotAbnormalTotal(slotAbnormalApps.size());
        abnormal.setInstanceAbnormalTotal(instanceAbnormalApps.size());
        abnormal.setAbnormalOverviewTotal(abnormalOverviewApps.size());
        abnormal.setHasSlotAbnormal(!slotAbnormalApps.isEmpty());
        abnormal.setHasInstanceAbnormal(!instanceAbnormalApps.isEmpty());

        List<DashboardAbnormalAppDto> apps = new ArrayList<>();
        for (Map<String, Object> item : preview) {
            long appId = MapUtils.getLongValue(item, "app_id", 0L);
            AppDesc appDesc = abnormalAppDescMap.get(appId);
            AppDetailVO appDetail = ctx.onlineAppDetails != null ? ctx.onlineAppDetails.get(appId) : null;

            DashboardAbnormalAppDto row = new DashboardAbnormalAppDto();
            row.setAppId(appId);
            if (appDesc != null) {
                row.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
                row.setAppName(appDesc.getName());
                row.setTypeDesc(appDesc.getTypeDesc());
                row.setVersionName(StringUtils.isBlank(appDesc.getVersionName()) ? "-" : appDesc.getVersionName());
            }
            if (appDetail != null) {
                row.setMasterNum(appDetail.getMasterNum());
                row.setSlaveNum(appDetail.getSlaveNum());
            }
            row.setTopologyAbnormal(MapUtils.getBooleanValue(item, "topology_abnormal", false));
            row.setInstanceAbnormal(MapUtils.getBooleanValue(item, "instance_abnormal", false));
            row.setUnknownStatus(MapUtils.getBooleanValue(item, "unknown_status", false));
            row.setUnknownStatusDetail(MapUtils.getString(item, "unknown_status_detail"));
            if (row.isInstanceAbnormal()) {
                row.setInstanceAbnormalCount(MapUtils.getInteger(item, "instance_abnormal_count"));
                row.setInstanceAbnormalDetail(MapUtils.getString(item, "instance_abnormal_detail"));
            }
            row.setProbeAbnormal(MapUtils.getBooleanValue(item, "probe_abnormal", false));
            row.setProbeAbnormalDetail(MapUtils.getString(item, "probe_abnormal_detail"));
            row.setSlotAbnormal(MapUtils.getBooleanValue(item, "slot_abnormal", false));
            if (row.isSlotAbnormal()) {
                row.setCoveredSlots(MapUtils.getInteger(item, "covered_slots"));
                row.setTotalSlots(MapUtils.getInteger(item, "total_slots"));
                row.setLostSlotsCount(MapUtils.getInteger(item, "lost_slots_count"));
                row.setFetchOk(MapUtils.getBoolean(item, "fetch_ok"));
                row.setStatusDesc(MapUtils.getString(item, "status_desc"));
                row.setLossDetail(MapUtils.getString(item, "loss_detail"));
            }
            apps.add(row);
        }
        abnormal.setApps(apps);
        return abnormal;
    }

    private DashboardChartsDto buildCharts(DashboardContext ctx) {
        DashboardChartsDto charts = new DashboardChartsDto();
        charts.setAppTypeDistribute(toChartItems(buildAppTypeDistribute(ctx.onlineApps)));
        charts.setAppMemUsageDistribute(toChartItems(
                buildAppMemUsageDistribute(ctx.onlineApps, ctx.onlineAppDetails, ctx.appClientGatherMap)));
        charts.setAppShardDistribute(toChartItems(buildAppShardDistribute(ctx.onlineApps, ctx.onlineAppDetails)));
        charts.setInstanceStatusDistribute(toChartItems(buildInstanceStatusDistribute()));
        return charts;
    }

    private List<ChartItemDto> toChartItems(List<ParamCount> list) {
        List<ChartItemDto> items = new ArrayList<>();
        if (list == null) {
            return items;
        }
        for (ParamCount pc : list) {
            items.add(new ChartItemDto(pc.getParam(), pc.getCount()));
        }
        return items;
    }

    private void enrichAbnormalAppDescMap(Map<Long, AppDesc> map, List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            long appId = MapUtils.getLongValue(item, "app_id", 0L);
            if (appId <= 0 || map.containsKey(appId)) {
                continue;
            }
            AppDesc appDesc = appService.getByAppId(appId);
            if (appDesc != null) {
                if (StringUtils.isBlank(appDesc.getVersionName())) {
                    String versionName = Optional.ofNullable(resourceService.getResourceById(appDesc.getVersionId()))
                            .map(ver -> ver.getName()).orElse("");
                    appDesc.setVersionName(versionName);
                }
                map.put(appId, appDesc);
            }
        }
    }

    private List<Map<String, Object>> mergeAbnormalOverviewApps(List<Map<String, Object>> topologyAppStats,
                                                                 List<Map<String, Object>> slotAbnormalApps,
                                                                 List<Map<String, Object>> instanceAbnormalApps,
                                                                 List<Map<String, Object>> probeAbnormalApps,
                                                                 List<Map<String, Object>> unknownStatusApps) {
        Map<Long, Map<String, Object>> mergedMap = new HashMap<>();

        if (topologyAppStats != null) {
            for (Map<String, Object> item : topologyAppStats) {
                long appId = MapUtils.getLongValue(item, "app_id", 0L);
                if (appId <= 0) {
                    continue;
                }
                Map<String, Object> row = new HashMap<>();
                row.put("app_id", appId);
                row.put("topology_abnormal", true);
                row.put("slot_abnormal", false);
                row.put("instance_abnormal", false);
                mergedMap.put(appId, row);
            }
        }

        if (slotAbnormalApps != null) {
            for (Map<String, Object> slotItem : slotAbnormalApps) {
                long appId = MapUtils.getLongValue(slotItem, "app_id", 0L);
                if (appId <= 0) {
                    continue;
                }
                Map<String, Object> row = mergedMap.get(appId);
                if (row == null) {
                    row = newHashMapAbnormalRow(appId);
                    mergedMap.put(appId, row);
                }
                row.put("slot_abnormal", true);
                row.put("covered_slots", slotItem.get("covered_slots"));
                row.put("total_slots", slotItem.get("total_slots"));
                row.put("lost_slots_count", slotItem.get("lost_slots_count"));
                row.put("fetch_ok", slotItem.get("fetch_ok"));
                row.put("status_desc", slotItem.get("status_desc"));
                row.put("loss_detail", slotItem.get("loss_detail"));
            }
        }

        if (instanceAbnormalApps != null) {
            for (Map<String, Object> instanceItem : instanceAbnormalApps) {
                long appId = MapUtils.getLongValue(instanceItem, "app_id", 0L);
                if (appId <= 0) {
                    continue;
                }
                Map<String, Object> row = mergedMap.get(appId);
                if (row == null) {
                    row = newHashMapAbnormalRow(appId);
                    mergedMap.put(appId, row);
                }
                row.put("instance_abnormal", true);
                row.put("instance_abnormal_detail", instanceItem.get("instance_abnormal_detail"));
                row.put("instance_abnormal_count", instanceItem.get("instance_abnormal_count"));
            }
        }

        if (probeAbnormalApps != null) {
            for (Map<String, Object> probeItem : probeAbnormalApps) {
                long appId = MapUtils.getLongValue(probeItem, "app_id", 0L);
                if (appId <= 0) continue;
                Map<String, Object> row = mergedMap.get(appId);
                if (row == null) {
                    row = newHashMapAbnormalRow(appId);
                    mergedMap.put(appId, row);
                }
                row.put("probe_abnormal", true);
                row.put("probe_abnormal_detail", probeItem.get("probe_abnormal_detail"));
            }
        }

        if (unknownStatusApps != null) {
            for (Map<String, Object> item : unknownStatusApps) {
                long appId = MapUtils.getLongValue(item, "app_id", 0L);
                if (appId <= 0) continue;
                Map<String, Object> row = mergedMap.get(appId);
                if (row == null) {
                    row = newHashMapAbnormalRow(appId);
                    mergedMap.put(appId, row);
                }
                row.put("unknown_status", true);
                row.put("unknown_status_detail", item.get("unknown_status_detail"));
            }
        }

        List<Map<String, Object>> merged = new ArrayList<>(mergedMap.values());
        merged.sort((a, b) -> {
            int scoreA = abnormalSortScore(a);
            int scoreB = abnormalSortScore(b);
            if (scoreA != scoreB) {
                return scoreB - scoreA;
            }
            return Long.compare(MapUtils.getLongValue(a, "app_id", 0L), MapUtils.getLongValue(b, "app_id", 0L));
        });
        return merged;
    }

    private Map<String, Object> newHashMapAbnormalRow(long appId) {
        Map<String, Object> row = new HashMap<>();
        row.put("app_id", appId);
        row.put("topology_abnormal", false);
        row.put("slot_abnormal", false);
        row.put("instance_abnormal", false);
        row.put("unknown_status", false);
        return row;
    }

    private List<Map<String, Object>> listUnknownStatusApps(List<AppDesc> onlineApps) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (onlineApps == null) return result;
        for (AppDesc app : onlineApps) {
            List<InstanceInfo> instances = instanceDao.getInstListByAppId(app.getAppId());
            boolean hasDataNode = false;
            boolean hasRunningDataNode = false;
            StringBuilder detail = new StringBuilder();
            if (instances != null) {
                for (InstanceInfo instance : instances) {
                    if (instance == null || instance.getType() == 5) continue;
                    hasDataNode = true;
                    if (detail.length() > 0) detail.append("；");
                    detail.append(instance.getIp()).append(':').append(instance.getPort());
                    if (instance.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                        hasRunningDataNode = true;
                    }
                }
            }
            // Use the persisted heartbeat status here. The Redis command probe can
            // return false for a healthy externally managed instance and would
            // incorrectly mark every node in the cluster as unknown.
            if (hasDataNode && !hasRunningDataNode) {
                Map<String, Object> row = new HashMap<>();
                row.put("app_id", app.getAppId());
                row.put("unknown_status_detail", detail.toString());
                result.add(row);
            }
        }
        return result;
    }

    private int abnormalSortScore(Map<String, Object> row) {
        int score = 0;
        if (MapUtils.getBooleanValue(row, "instance_abnormal", false)) {
            score += 100;
        }
        if (MapUtils.getBooleanValue(row, "slot_abnormal", false)) {
            score += 50;
        }
        if (MapUtils.getBooleanValue(row, "topology_abnormal", false)) {
            score += 10;
        }
        return score;
    }

    private List<Map<String, Object>> listInstanceAbnormalApps(List<AppDesc> onlineApps) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<InstanceInfo> abnormalInstances = instanceDao.getAllHeartStopInstance();
        if (abnormalInstances == null || abnormalInstances.isEmpty()) {
            return result;
        }
        Map<Long, AppDesc> onlineAppMap = onlineApps.stream()
                .filter(this::isOnlineApp)
                .collect(Collectors.toMap(AppDesc::getAppId, a -> a, (a, b) -> a));

        Map<Long, List<InstanceInfo>> byAppId = new HashMap<>();
        for (InstanceInfo info : abnormalInstances) {
            if (info == null || info.getAppId() <= 0) {
                continue;
            }
            // Sentinel processes are not Redis data nodes and are excluded from
            // the instance heartbeat overview.
            if (info.getType() == 5) {
                continue;
            }
            if (!onlineAppMap.containsKey(info.getAppId())) {
                continue;
            }
            if (info.getStatus() != InstanceStatusEnum.ERROR_STATUS.getStatus()) {
                continue;
            }
            byAppId.computeIfAbsent(info.getAppId(), k -> new ArrayList<>()).add(info);
        }
        for (Map.Entry<Long, List<InstanceInfo>> entry : byAppId.entrySet()) {
            List<InstanceInfo> instList = entry.getValue();
            if (instList == null || instList.isEmpty()) {
                continue;
            }
            StringBuilder detail = new StringBuilder();
            for (InstanceInfo inst : instList) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append(inst.getIp()).append(':').append(inst.getPort());
                if (inst.getId() > 0) {
                    detail.append("(id=").append(inst.getId()).append(')');
                }
            }
            Map<String, Object> row = new HashMap<>();
            row.put("app_id", entry.getKey());
            row.put("instance_abnormal_detail", detail.toString());
            row.put("instance_abnormal_count", instList.size());
            result.add(row);
        }
        result.sort((a, b) -> Integer.compare(
                MapUtils.getIntValue(b, "instance_abnormal_count", 0),
                MapUtils.getIntValue(a, "instance_abnormal_count", 0)));
        return result;
    }

    /** 外部纳管节点使用最近一次持久化心跳状态进入全局异常总览。 */
    private List<Map<String, Object>> listExternalAbnormalApps(List<AppDesc> onlineApps) {
        if (onlineApps == null || onlineApps.isEmpty() || externalRedisCenter == null) {
            return Collections.emptyList();
        }
        Set<Long> onlineAppIds = onlineApps.stream().filter(this::isOnlineApp)
                .map(AppDesc::getAppId).collect(Collectors.toSet());
        if (onlineAppIds.isEmpty()) return Collections.emptyList();

        Map<Long, List<String>> failedNodes = new HashMap<>();
        try {
            List<com.shcj.cache.web.vo.ExternalNodeVO> nodes = externalRedisCenter.listExternalNodes("");
            if (nodes == null) return Collections.emptyList();
            for (com.shcj.cache.web.vo.ExternalNodeVO node : nodes) {
                if (node == null || !onlineAppIds.contains(node.getAppId())
                        || "sentinel".equalsIgnoreCase(node.getNodeTypeDesc())
                        || node.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                    continue;
                }
                failedNodes.computeIfAbsent(node.getAppId(), key -> new ArrayList<>())
                        .add(node.getIp() + ":" + node.getPort());
            }
        } catch (Exception e) {
            logger.warn("collect external probe abnormal overview failed: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, List<String>> entry : failedNodes.entrySet()) {
            Map<String, Object> row = new HashMap<>();
            row.put("app_id", entry.getKey());
            row.put("probe_abnormal_detail", String.join("；", entry.getValue()));
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> listClusterSlotAbnormalApps(List<AppDesc> onlineApps) {
        if (onlineApps == null || onlineApps.isEmpty()) {
            return Collections.emptyList();
        }
        List<AppDesc> clusterApps = onlineApps.stream()
                .filter(appDesc -> appDesc != null
                        && appDesc.getType() == ConstUtils.CACHE_TYPE_REDIS_CLUSTER
                        && AppStatusEnum.STATUS_PUBLISHED.getStatus() == appDesc.getStatus())
                .collect(Collectors.toList());
        if (clusterApps.isEmpty()) {
            return Collections.emptyList();
        }

        List<Future<Map<String, Object>>> futures = new ArrayList<>(clusterApps.size());
        for (AppDesc appDesc : clusterApps) {
            futures.add(clusterCheckPool.submit(() -> checkClusterSlotAbnormal(appDesc.getAppId())));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Future<Map<String, Object>> future : futures) {
            try {
                Map<String, Object> row = future.get(CLUSTER_CHECK_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (row != null) {
                    result.add(row);
                }
            } catch (TimeoutException e) {
                future.cancel(true);
                logger.warn("cluster slot check timeout");
            } catch (Exception e) {
                logger.warn("cluster slot check error: {}", e.getMessage());
            }
        }
        return result;
    }

    private Map<String, Object> checkClusterSlotAbnormal(long appId) {
        final int totalSlots = 16384;
        try {
            Map<String, InstanceSlotModel> clusterSlotsMap = redisCenter.getClusterSlotsMap(appId);
            Map<String, String> lossSlotsSegmentMap = redisCenter.getClusterLossSlots(appId);
            int coveredSlots = 0;
            if (MapUtils.isNotEmpty(clusterSlotsMap)) {
                for (InstanceSlotModel model : clusterSlotsMap.values()) {
                    if (model.getSlotList() != null) {
                        coveredSlots += model.getSlotList().size();
                    }
                }
            }
            int lostSlotsCount = Math.max(0, totalSlots - coveredSlots);
            boolean fetchOk = MapUtils.isNotEmpty(clusterSlotsMap);
            boolean slotComplete = fetchOk && lostSlotsCount == 0;
            if (fetchOk && slotComplete && MapUtils.isEmpty(lossSlotsSegmentMap)) {
                return null;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("app_id", appId);
            row.put("covered_slots", coveredSlots);
            row.put("total_slots", totalSlots);
            row.put("lost_slots_count", lostSlotsCount);
            row.put("fetch_ok", fetchOk);
            if (!fetchOk) {
                row.put("status_desc", "无法获取槽位信息");
            } else {
                row.put("status_desc", "缺失 " + lostSlotsCount + " 个槽位");
            }
            if (MapUtils.isNotEmpty(lossSlotsSegmentMap)) {
                StringBuilder lossDetail = new StringBuilder();
                for (Map.Entry<String, String> entry : lossSlotsSegmentMap.entrySet()) {
                    if (lossDetail.length() > 0) {
                        lossDetail.append("; ");
                    }
                    lossDetail.append(entry.getKey()).append(": ").append(entry.getValue());
                    if (lossDetail.length() > 240) {
                        lossDetail.append("...");
                        break;
                    }
                }
                row.put("loss_detail", lossDetail.toString());
            }
            return row;
        } catch (Exception e) {
            logger.error("checkClusterSlotAbnormal appId:{} error:{}", appId, e.getMessage());
            return null;
        }
    }

    private boolean isOnlineApp(AppDesc appDesc) {
        return appDesc != null
                && AppStatusEnum.STATUS_PUBLISHED.getStatus() == appDesc.getStatus();
    }

    private boolean isOpsApp(AppDesc appDesc) {
        return appDesc != null
                && !appDesc.isTestOk()
                && AppStatusEnum.STATUS_PUBLISHED.getStatus() == appDesc.getStatus();
    }

    private List<ParamCount> buildAppTypeDistribute(List<AppDesc> onlineApps) {
        Map<String, Integer> counter = new TreeMap<>();
        for (AppDesc appDesc : onlineApps) {
            if (!isOnlineApp(appDesc)) {
                continue;
            }
            String typeDesc = appDesc.getTypeDesc();
            if (StringUtils.isBlank(typeDesc)) {
                typeDesc = "其他";
            }
            counter.merge(typeDesc, 1, Integer::sum);
        }
        return toParamCountList(counter);
    }

    private List<ParamCount> buildAppMemUsageDistribute(List<AppDesc> onlineApps,
                                                        Map<Long, AppDetailVO> onlineAppDetails,
                                                        Map<Long, Map<String, Object>> appClientGatherMap) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        counter.put("<60%", 0);
        counter.put("60-80%", 0);
        counter.put("80-90%", 0);
        counter.put("≥90%", 0);
        counter.put("无数据", 0);

        for (AppDesc appDesc : onlineApps) {
            if (!isOnlineApp(appDesc)) {
                continue;
            }
            long appId = appDesc.getAppId();
            double ratio = -1;
            if (appClientGatherMap != null && appClientGatherMap.get(appId) != null) {
                ratio = MapUtils.getDoubleValue(appClientGatherMap.get(appId), "mem_used_ratio", -1);
            }
            if (ratio < 0 && onlineAppDetails != null && onlineAppDetails.get(appId) != null) {
                ratio = onlineAppDetails.get(appId).getMemUsePercent();
            }
            if (ratio < 0) {
                counter.merge("无数据", 1, Integer::sum);
            } else if (ratio < 60) {
                counter.merge("<60%", 1, Integer::sum);
            } else if (ratio < 80) {
                counter.merge("60-80%", 1, Integer::sum);
            } else if (ratio < 90) {
                counter.merge("80-90%", 1, Integer::sum);
            } else {
                counter.merge("≥90%", 1, Integer::sum);
            }
        }
        return toParamCountList(counter);
    }

    private List<ParamCount> buildAppShardDistribute(List<AppDesc> onlineApps,
                                                     Map<Long, AppDetailVO> onlineAppDetails) {
        Map<Integer, Integer> counter = new TreeMap<>();
        for (AppDesc appDesc : onlineApps) {
            if (!isOnlineApp(appDesc)) {
                continue;
            }
            AppDetailVO detail = onlineAppDetails != null ? onlineAppDetails.get(appDesc.getAppId()) : null;
            int masterNum = detail != null ? detail.getMasterNum() : 0;
            if (masterNum <= 0) {
                masterNum = 1;
            }
            counter.merge(masterNum, 1, Integer::sum);
        }
        Map<String, Integer> labeled = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : counter.entrySet()) {
            labeled.put(entry.getKey() + " 分片", entry.getValue());
        }
        return toParamCountList(labeled);
    }

    private List<ParamCount> buildInstanceStatusDistribute() {
        Map<String, Integer> counter = new LinkedHashMap<>();
        counter.put(InstanceStatusEnum.GOOD_STATUS.getInfo(), 0);
        counter.put(InstanceStatusEnum.ERROR_STATUS.getInfo(), 0);
        counter.put(InstanceStatusEnum.OFFLINE_STATUS.getInfo(), 0);
        counter.put(InstanceStatusEnum.FORGET_STATUS.getInfo(), 0);
        counter.put(InstanceStatusEnum.ALLOCATE_STATUS.getInfo(), 0);

        List<InstanceInfo> instanceList = instanceDao.getAllInsts();
        if (instanceList != null) {
            for (InstanceInfo instanceInfo : instanceList) {
                InstanceStatusEnum statusEnum = InstanceStatusEnum.getByStatus(instanceInfo.getStatus());
                String label = statusEnum != null ? statusEnum.getInfo() : "未知";
                counter.merge(label, 1, Integer::sum);
            }
        }
        return toParamCountList(counter);
    }

    private List<ParamCount> toParamCountList(Map<String, Integer> counter) {
        List<ParamCount> result = new ArrayList<>();
        if (counter == null) {
            return result;
        }
        for (Map.Entry<String, Integer> entry : counter.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            result.add(new ParamCount(entry.getKey(), entry.getValue(), ""));
        }
        return result;
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @PreDestroy
    public void shutdown() {
        clusterCheckPool.shutdownNow();
        detailsPool.shutdownNow();
    }

    private static final class DashboardContext {
        private String topologySearchDate;
        private List<AppDesc> onlineApps;
        private Map<Long, AppDetailVO> onlineAppDetails;
        private Map<Long, Map<String, Object>> appClientGatherMap;
        private Map<String, List<Map<String, Object>>> appClientGatherStatGroup;
    }

    private static final class DetailsCache {
        private final long expireAt;
        private final DashboardDetailsDto data;

        private DetailsCache(long expireAt, DashboardDetailsDto data) {
            this.expireAt = expireAt;
            this.data = data;
        }
    }
}
