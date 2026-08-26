package com.shcj.cache.web.service;

import com.shcj.cache.dao.AppClientStatisticGatherDao;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.entity.AppClientStatisticGather;
import com.shcj.cache.entity.AppDesc;
import org.apache.commons.lang3.StringUtils;
import com.shcj.cache.entity.SystemResource;
import com.shcj.cache.entity.TimeBetween;
import com.shcj.cache.stats.admin.CoreAppsStatCenter;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.task.tasks.daily.TopologyExamTask;
import com.shcj.cache.util.AppClusterNoSupport;
import com.shcj.cache.web.controller.api.dto.ServerStatAppRowDto;
import com.shcj.cache.web.controller.api.dto.ServerStatAppsDto;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.AppDetailVO;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.ParseException;
import java.util.*;

/**
 * server 统计 API（M2）
 */
@Service
public class AppStatApiService {

    @Resource
    private AppDao appDao;

    @Resource(name = "appService")
    private AppService appService;

    @Resource
    private ResourceService resourceService;

    @Resource(name = "appStatsCenter")
    private AppStatsCenter appStatsCenter;

    @Autowired
    private AppClientStatisticGatherDao appClientStatisticGatherDao;

    @Autowired
    private TopologyExamTask topologyExamTask;

    @Autowired
    private CoreAppsStatCenter coreAppsStatCenter;

    public ServerStatAppsDto listServerStats(Long appId, String searchDate) {
        TimeBetween timeBetween = new TimeBetween();
        try {
            timeBetween = DateUtil.fillWithDateFormat(searchDate);
        } catch (ParseException ignored) {
            // use default from DateUtil
        }
        String resolvedDate = timeBetween.getFormatStartDate();

        Long queryAppId = null;
        if (appId != null && appId > 0) {
            AppDesc resolved = appService.resolveApp(appId);
            if (resolved == null) {
                ServerStatAppsDto empty = new ServerStatAppsDto();
                empty.setSearchDate(resolvedDate);
                return empty;
            }
            queryAppId = resolved.getAppId();
        }

        List<AppDesc> appDescList = appDao.getOnlineApps();
        if (appDescList == null) {
            appDescList = Collections.emptyList();
        }
        for (AppDesc appDesc : appDescList) {
            // versionName 已由 AppDao 从 app_desc.redis_version 映射进来（纳管集群的版本来自实例探测）。
            // 只有当 version_id 能在 system_resource 里查到时才覆盖；查不到就保留探测值——
            // 纳管集群的 version_id 恒为 0，无条件覆盖会把版本清成空串。
            SystemResource version = resourceService.getResourceById(appDesc.getVersionId());
            if (version != null && StringUtils.isNotBlank(version.getName())) {
                appDesc.setVersionName(version.getName());
            }
        }

        Map<Long, AppDetailVO> appDetailVOMap = appStatsCenter.getOnlineAppDetails();
        if (appDetailVOMap == null) {
            appDetailVOMap = Collections.emptyMap();
        }

        long filterAppId = queryAppId != null ? queryAppId : -1L;
        Map<Long, Map<String, Object>> appClientGatherStatMap =
                appService.getAppClientStatGather(filterAppId, resolvedDate);
        if (appClientGatherStatMap == null) {
            appClientGatherStatMap = Collections.emptyMap();
        }

        Map<Long, Map<String, Object>> appMemUsageRatioMap = buildMemUsageRatioMap(
                appDescList, appDetailVOMap, appClientGatherStatMap);

        ServerStatAppsDto result = new ServerStatAppsDto();
        result.setSearchDate(resolvedDate);
        for (AppDesc appDesc : appDescList) {
            if (queryAppId != null && !queryAppId.equals(appDesc.getAppId())) {
                continue;
            }
            AppDetailVO detail = appDetailVOMap.get(appDesc.getAppId());
            Map<String, Object> gather = appClientGatherStatMap.get(appDesc.getAppId());
            Map<String, Object> memRatio = appMemUsageRatioMap.get(appDesc.getAppId());
            result.getItems().add(buildRow(appDesc, detail, gather, memRatio));
        }
        return result;
    }

    public void sendDailyEmail(String searchDate) {
        coreAppsStatCenter.sendExpAppsStatDataEmail(searchDate);
    }

    public void updateTopologyExam() {
        List<AppClientStatisticGather> topologyExamList = topologyExamTask.checkAppsTopology(new Date());
        if (CollectionUtils.isNotEmpty(topologyExamList)) {
            appClientStatisticGatherDao.batchSaveTopologyExam(topologyExamList);
        }
    }

    private Map<Long, Map<String, Object>> buildMemUsageRatioMap(List<AppDesc> appDescList,
                                                                 Map<Long, AppDetailVO> appDetailVOMap,
                                                                 Map<Long, Map<String, Object>> appClientGatherStatMap) {
        Map<Long, Map<String, Object>> appMemUsageRatioMap = new HashMap<>();
        for (AppDesc appDesc : appDescList) {
            Map<String, Object> gather = appClientGatherStatMap.get(appDesc.getAppId());
            AppDetailVO detail = appDetailVOMap.get(appDesc.getAppId());
            if (gather == null || detail == null) {
                continue;
            }
            float mem = detail.getMem() / 1024f;
            long usedMemoryRaw = toLong(gather.get("used_memory"));
            long usedMemRssRaw = toLong(gather.get("used_memory_rss"));
            float usedMemory = usedMemoryRaw / 1024f / 1024f / 1024f;
            float usedMemRss = usedMemRssRaw / 1024f / 1024f / 1024f;
            double memUsageRatio = mem == 0 ? 0 : usedMemory / mem * 100.0;
            double memUsageRatioRss = mem == 0 ? 0 : usedMemRss / mem * 100.0;

            Map<String, Object> memUsageRatioMap = new HashMap<>();
            memUsageRatioMap.put("mem", mem);
            memUsageRatioMap.put("usedMemory", usedMemory);
            memUsageRatioMap.put("usedMemRss", usedMemRss);
            memUsageRatioMap.put("memUsageRatio", memUsageRatio);
            memUsageRatioMap.put("memUsageRatioRss", memUsageRatioRss);
            appMemUsageRatioMap.put(appDesc.getAppId(), memUsageRatioMap);
        }
        return appMemUsageRatioMap;
    }

    private ServerStatAppRowDto buildRow(AppDesc appDesc, AppDetailVO detail,
                                         Map<String, Object> gather, Map<String, Object> memRatio) {
        ServerStatAppRowDto row = new ServerStatAppRowDto();
        row.setAppId(appDesc.getAppId());
        row.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
        row.setAppName(appDesc.getName());
        row.setTestApp(appDesc.getIsTest() == 1);
        row.setTestLabel(appDesc.getIsTest() == 1 ? "测试" : "正式");
        row.setTypeDesc(appDesc.getTypeDesc());
        row.setVersionName(appDesc.getVersionName());

        int masterNum = detail != null ? detail.getMasterNum() : 0;
        int slaveNum = detail != null ? detail.getSlaveNum() : 0;
        row.setMasterNum(masterNum);
        row.setSlaveNum(slaveNum);
        row.setNodeBalanced(masterNum == slaveNum);

        double shardMemGb = 0;
        if (detail != null && masterNum > 0) {
            shardMemGb = round2(detail.getMem() / (double) masterNum / 1024);
        }
        row.setShardMemGb(shardMemGb);
        row.setShardSpec(masterNum + " * " + shardMemGb + "G");

        if (memRatio != null) {
            row.setMemTotalGb(toDouble(memRatio.get("mem")));
            row.setMemUsedGb(toDouble(memRatio.get("usedMemory")));
            row.setMemUsedRssGb(toDouble(memRatio.get("usedMemRss")));
            row.setMemUsageRatio(toDouble(memRatio.get("memUsageRatio")));
            row.setMemUsageRatioRss(toDouble(memRatio.get("memUsageRatioRss")));
        }
        row.setMemUsePercent(detail != null ? detail.getMemUsePercent() : 0);

        if (gather != null) {
            row.setSlowLogCount(toLong(gather.get("slow_log_count")));
            row.setConnectedClients(toLong(gather.get("connected_clients")));
            row.setObjectSize(toLong(gather.get("object_size")));
            row.setUsedMemoryMb(round2(toLong(gather.get("used_memory")) / 1024.0 / 1024.0));
            row.setUsedMemoryRssMb(round2(toLong(gather.get("used_memory_rss")) / 1024.0 / 1024.0));
            row.setAvgMemFragRatio(toDouble(gather.get("avg_mem_frag_ratio")));
            row.setMaxCpuSys(toDouble(gather.get("max_cpu_sys")));
            row.setMaxCpuUser(toDouble(gather.get("max_cpu_user")));

            Object topologyResult = gather.get("topology_exam_result");
            if (topologyResult instanceof Number) {
                row.setTopologyExamResult(((Number) topologyResult).intValue());
            }
        }
        row.setTopologyExamLabel(resolveTopologyLabel(row.getTopologyExamResult()));
        return row;
    }

    private String resolveTopologyLabel(Integer result) {
        if (result == null) {
            return "未检测";
        }
        if (result == 0) {
            return "正常";
        }
        if (result == 1) {
            return "异常";
        }
        return "未检测";
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0D;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0D;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
