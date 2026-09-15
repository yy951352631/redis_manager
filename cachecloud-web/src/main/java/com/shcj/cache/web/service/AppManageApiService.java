package com.shcj.cache.web.service;

import com.shcj.cache.constant.AppStatusEnum;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.dao.ExternalRedisDao;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.dao.AppToUserDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.app.ExternalRedisCenter;
import com.shcj.cache.stats.app.AppDeployCenter;
import com.shcj.cache.web.controller.api.dto.AppDetailDto;
import com.shcj.cache.web.controller.api.dto.AppDetailTabDto;
import com.shcj.cache.web.controller.api.dto.AppListItemDto;
import com.shcj.cache.web.controller.api.dto.AppListPageDto;
import com.shcj.cache.web.controller.api.dto.AppOfflineResultDto;
import com.shcj.cache.util.AppClusterNoSupport;
import com.shcj.cache.web.util.Page;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.AppDetailVO;
import com.shcj.cache.web.vo.InstanceIpSearchVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 集群管理 API 业务（M3），列表逻辑迁移自已下线的 JSP 控制器 AppManageController
 */
@Service
public class AppManageApiService {

    @Resource(name = "appService")
    private AppService appService;

    @Resource
    private ExternalRedisDao externalRedisDao;

    @Resource(name = "externalRedisCenter")
    private ExternalRedisCenter externalRedisCenter;

    @Resource(name = "instanceDao")
    private InstanceDao instanceDao;

    @Resource
    private AppDao appDao;

    @Resource
    private AppToUserDao appToUserDao;

    @Resource(name = "appStatsCenter")
    private AppStatsCenter appStatsCenter;

    @Resource(name = "appDeployCenter")
    private AppDeployCenter appDeployCenter;

    @Value("${cachecloud.web.host-ops-enabled:false}")
    private boolean hostOpsEnabled;

    public AppListPageDto listApps(AppUser currentUser, String ip, String appParam, Integer appStatus,
                                   int pageNo, int pageSize) {
        String searchIp = StringUtils.trimToEmpty(ip);
        String searchAppParam = StringUtils.trimToEmpty(appParam);
        int statusFilter = appStatus != null ? appStatus : AppStatusEnum.STATUS_PUBLISHED.getStatus();
        Integer queryStatus = statusFilter < 0 ? null
                : statusFilter == AppStatusEnum.STATUS_UNKNOWN.getStatus()
                || statusFilter == AppStatusEnum.STATUS_ABNORMAL.getStatus()
                ? AppStatusEnum.STATUS_PUBLISHED.getStatus() : statusFilter;

        Map<Long, InstanceIpSearchVO> ipHitMap = new HashMap<>();
        if (StringUtils.isNotBlank(searchIp)) {
            for (InstanceIpSearchVO hit : externalRedisCenter.searchByInstanceIp(searchIp)) {
                ipHitMap.put(hit.getAppId(), hit);
            }
        }

        AppSearch appSearch = new AppSearch();
        appSearch.setAppStatus(queryStatus);
        if (StringUtils.isNotBlank(searchAppParam)) {
            if (StringUtils.isNumeric(searchAppParam)) {
                long num = Long.parseLong(searchAppParam);
                if (AppClusterNoSupport.isClusterNo(num)) {
                    appSearch.setClusterNo((int) num);
                } else {
                    appSearch.setAppId(num);
                }
            } else {
                appSearch.setAppName(searchAppParam);
            }
        }

        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = pageSize > 0 ? Math.min(pageSize, 500) : 20;

        List<AppDesc> apps;
        int totalCount;
        Page page;

        if (StringUtils.isNotBlank(searchIp)) {
            if (ipHitMap.isEmpty()) {
                AppListPageDto empty = new AppListPageDto();
                empty.setPageNo(safePageNo);
                empty.setPageSize(safePageSize);
                empty.setTotalCount(0);
                empty.setTotalPages(0);
                empty.setHostOpsEnabled(hostOpsEnabled);
                return empty;
            }
            List<AppDesc> allApps = appService.getAppDescList(currentUser, appSearch);
            if (allApps == null) {
                allApps = Collections.emptyList();
            }
            final Set<Long> ipAppIds = ipHitMap.keySet();
            List<AppDesc> filtered = allApps.stream()
                    .filter(a -> ipAppIds.contains(a.getAppId()))
                    .collect(Collectors.toList());
            totalCount = filtered.size();
            page = new Page(safePageNo, safePageSize, totalCount);
            int start = page.getStart();
            if (start >= totalCount) {
                apps = Collections.emptyList();
            } else {
                apps = filtered.subList(start, Math.min(start + safePageSize, totalCount));
            }
        } else {
            totalCount = appService.getAppDescCount(currentUser, appSearch);
            page = new Page(safePageNo, safePageSize, totalCount);
            appSearch.setPage(page);
            apps = appService.getAppDescList(currentUser, appSearch);
            if (apps == null) {
                apps = Collections.emptyList();
            }
        }

        Map<Long, ExternalRedis> externalByAppId = new HashMap<>();
        for (ExternalRedis ext : externalRedisDao.listAll()) {
            if (ext.getAppId() != null) {
                externalByAppId.put(ext.getAppId(), ext);
            }
        }

        List<AppListItemDto> items = new ArrayList<>();
        for (AppDesc appDesc : apps) {
            AppListItemDto item = buildListItem(appDesc, externalByAppId.get(appDesc.getAppId()),
                    ipHitMap.get(appDesc.getAppId()));
            if (statusFilter < 0
                    || statusFilter == item.getRuntimeStatus()
                    || (statusFilter == AppStatusEnum.STATUS_UNKNOWN.getStatus()
                    && item.getRuntimeStatus() == AppStatusEnum.STATUS_UNKNOWN.getStatus())
                    || (statusFilter == AppStatusEnum.STATUS_ABNORMAL.getStatus()
                    && item.getRuntimeStatus() == AppStatusEnum.STATUS_ABNORMAL.getStatus())) {
                items.add(item);
            }
        }

        AppListPageDto result = new AppListPageDto();
        result.setItems(items);
        result.setPageNo(safePageNo);
        result.setPageSize(safePageSize);
        result.setTotalCount(totalCount);
        result.setTotalPages(page.getTotalPages());
        result.setHostOpsEnabled(hostOpsEnabled);
        return result;
    }

    public AppOfflineResultDto offlineApp(long appId, AppUser user, Long appAuditId) {
        long taskId = appDeployCenter.offLineApp(appId, user, appAuditId);
        AppOfflineResultDto dto = new AppOfflineResultDto();
        dto.setAppId(appId);
        dto.setTaskId(taskId);
        dto.setMessage("下线任务已提交，后台执行中，taskId:" + taskId);
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    public void permanentlyDeleteOfflineApp(long appId) {
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            throw new IllegalArgumentException("集群不存在");
        }
        if (appDesc.getStatus() != AppStatusEnum.STATUS_OFFLINE.getStatus()) {
            throw new IllegalArgumentException("仅允许彻底删除已下线集群");
        }
        externalRedisDao.deleteByAppId(appId);
        instanceDao.deleteByAppId(appId);
        appToUserDao.deleteByAppId(appId);
        appDao.delete(appId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AppOfflineResultDto recoverApp(long appId, AppUser user) {
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            throw new IllegalArgumentException("cluster not found");
        }
        if (appDesc.getStatus() != AppStatusEnum.STATUS_OFFLINE.getStatus()) {
            throw new IllegalArgumentException("only offline cluster can be recovered");
        }

        List<InstanceInfo> instances = instanceDao.getInstListByAppId(appId);
        if ((instances == null || instances.isEmpty()) && externalRedisDao.getByAppId(appId) != null) {
            externalRedisCenter.repairInstances(appId);
            instances = instanceDao.getInstListByAppId(appId);
        }
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("cluster has no nodes to recover");
        }

        for (InstanceInfo inst : instances) {
            InstanceInfo exists = instanceDao.getAllInstByIpAndPort(inst.getIp(), inst.getPort());
            if (exists == null || exists.getAppId() == appId) {
                continue;
            }
            int status = exists.getStatus();
            if (status == InstanceStatusEnum.ERROR_STATUS.getStatus()
                    || status == InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                AppDesc occupied = appService.getByAppId(exists.getAppId());
                String occupiedName = occupied != null ? occupied.getName() : String.valueOf(exists.getAppId());
                throw new IllegalArgumentException("node " + inst.getHostPort()
                        + " is already used by online cluster " + occupiedName);
            }
        }

        appDesc.setStatus(AppStatusEnum.STATUS_PUBLISHED.getStatus());
        appDesc.setPassedTime(new Date());
        appService.update(appDesc);
        for (InstanceInfo inst : instances) {
            if (inst.getStatus() != InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                instanceDao.updateStatus(appId, inst.getIp(), inst.getPort(),
                        InstanceStatusEnum.GOOD_STATUS.getStatus());
            }
        }
        if (externalRedisDao.getByAppId(appId) != null) {
            externalRedisCenter.collectNow(appId);
        }

        AppOfflineResultDto dto = new AppOfflineResultDto();
        dto.setAppId(appId);
        dto.setTaskId(0L);
        dto.setMessage("cluster recovered");
        return dto;
    }

    private AppListItemDto buildListItem(AppDesc appDesc, ExternalRedis externalRedis, InstanceIpSearchVO ipHit) {
        AppListItemDto row = new AppListItemDto();
        long appId = appDesc.getAppId();
        row.setAppId(appId);
        row.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
        row.setAppName(appDesc.getName());
        row.setTypeDesc(appDesc.getTypeDesc());
        row.setRuntimeStatus(appDesc.getStatus());
        AppStatusEnum statusEnum = AppStatusEnum.getByStatus(appDesc.getStatus());
        row.setRuntimeStatusLabel(statusEnum != null ? statusEnum.getInfo() : String.valueOf(appDesc.getStatus()));
        row.setExternalManaged(externalRedis != null);
        row.setSourceLabel(externalRedis != null ? "外部纳管" : "平台部署");

        List<InstanceInfo> instances = instanceDao.getInstListByAppId(appId);
        if (instances == null) {
            instances = Collections.emptyList();
        }
        row.setInstanceCount(instances.size());
        List<String> allNodeLines = buildAllNodeLines(instances, externalRedis);
        row.getAllNodeLines().addAll(allNodeLines);
        int showMax = 2;
        int visibleCount = Math.min(showMax, allNodeLines.size());
        for (int i = 0; i < visibleCount; i++) {
            row.getNodeLines().add(allNodeLines.get(i));
        }
        if (allNodeLines.size() > showMax) {
            row.setExtraNodeCount(allNodeLines.size() - showMax);
            row.getExtraNodeLines().addAll(allNodeLines.subList(showMax, allNodeLines.size()));
        }

        if (ipHit != null && CollectionUtils.isNotEmpty(ipHit.getMatchedNodes())) {
            row.setMatchedNodes(new ArrayList<>(ipHit.getMatchedNodes()));
        }

        if (appDesc.getStatus() == AppStatusEnum.STATUS_PUBLISHED.getStatus()) {
            String abnormalDetail = buildAbnormalDataNodeDetail(instances);
            if (StringUtils.isNotBlank(abnormalDetail)) {
                row.setRuntimeStatus(AppStatusEnum.STATUS_ABNORMAL.getStatus());
                row.setRuntimeStatusLabel(AppStatusEnum.STATUS_ABNORMAL.getInfo());
                row.setRuntimeStatusDetail("目标节点探活失败：" + abnormalDetail);
            } else if (!hasRunningDataNode(instances)) {
                row.setRuntimeStatus(AppStatusEnum.STATUS_UNKNOWN.getStatus());
                row.setRuntimeStatusLabel(AppStatusEnum.STATUS_UNKNOWN.getInfo());
            }
            AppDetailVO detail = appStatsCenter.getAppDetail(appId);
            if (detail != null) {
                row.setMem(detail.getMem());
                row.setMemUsePercent(detail.getMemUsePercent());
                row.setHighestMemFragRatio(detail.getHighestMemFragRatio());
                row.setInstIdWithHighestMemFragRatio(detail.getInstIdWithHighestMemFragRatio());
                row.setHitPercent(detail.getHitPercent());
                row.setKeyspaceHits(detail.getKeyspaceHits());
                row.setKeyspaceMisses(detail.getKeyspaceMisses());
                row.setQps(detail.getQps());
                row.setKeyCount(detail.getCurrentKeyCount());
                row.setCpuUsePercent(detail.getCpuUsePercent());
                row.setUptimeSeconds(detail.getUptimeSeconds());
                row.setHasOfflineInstances(CollectionUtils.isNotEmpty(detail.getOffLineInstances()));
                if (detail.getAppDesc() != null) {
                    row.setVersionName(detail.getAppDesc().getVersionName());
                    row.setAppRunDays(detail.getAppDesc().getAppRunDays());
                }
            } else if (row.getRuntimeStatus() != AppStatusEnum.STATUS_UNKNOWN.getStatus()
                    && row.getRuntimeStatus() != AppStatusEnum.STATUS_ABNORMAL.getStatus()) {
                row.setRuntimeStatus(AppStatusEnum.STATUS_UNKNOWN.getStatus());
                row.setRuntimeStatusLabel(AppStatusEnum.STATUS_UNKNOWN.getInfo());
            }
        }
        applyPersistedRedisVersion(row, appDesc);
        return row;
    }

    private List<String> buildAllNodeLines(List<InstanceInfo> instances, ExternalRedis externalRedis) {
        List<String> lines = new ArrayList<>();
        if (instances != null) {
            for (InstanceInfo inst : instances) {
                lines.add(formatInstanceNodeLine(inst));
            }
        }
        if (lines.isEmpty() && externalRedis != null
                && StringUtils.isNotBlank(externalRedis.getInstanceInfo())) {
            for (String raw : externalRedis.getInstanceInfo().split("\\r?\\n")) {
                if (StringUtils.isNotBlank(raw)) {
                    lines.add(raw.trim());
                }
            }
        }
        return lines;
    }

    private String formatInstanceNodeLine(InstanceInfo inst) {
        String line = inst.getIp() + ":" + inst.getPort();
        if (inst.getType() == 5) {
            line += "(sentinel:" + inst.getCmd() + ")";
        }
        return line;
    }

    public AppDetailDto getAppDetail(long idOrClusterNo) {
        AppDesc appDesc = appService.getByAppId(idOrClusterNo);
        if (appDesc == null) {
            return null;
        }
        long appId = appDesc.getAppId();
        ExternalRedis externalRedis = externalRedisDao.getByAppId(appId);
        List<InstanceInfo> instances = instanceDao.getInstListByAppId(appId);
        if (instances == null) {
            instances = Collections.emptyList();
        }

        AppDetailDto dto = new AppDetailDto();
        dto.setAppId(appId);
        dto.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
        dto.setAppName(appDesc.getName());
        dto.setIntro(appDesc.getIntro());
        dto.setType(appDesc.getType());
        dto.setTypeDesc(appDesc.getTypeDesc());
        dto.setRuntimeStatus(appDesc.getStatus());
        AppStatusEnum statusEnum = AppStatusEnum.getByStatus(appDesc.getStatus());
        dto.setRuntimeStatusLabel(statusEnum != null ? statusEnum.getInfo() : String.valueOf(appDesc.getStatus()));
        dto.setVersionName(appDesc.getVersionName());
        dto.setExternalManaged(externalRedis != null);
        dto.setSourceLabel(externalRedis != null ? "外部纳管" : "平台部署");
        dto.setInstallModuleFlag(appService.isInstallModule(appId));
        dto.setInstanceCount(instances.size());
        dto.setOpsEnabled(appDesc.getStatus() == AppStatusEnum.STATUS_PUBLISHED.getStatus());
        dto.setDefaultTab("app_stat");
        dto.setTabs(buildDetailTabs(appId));

        if (appDesc.getStatus() == AppStatusEnum.STATUS_PUBLISHED.getStatus()) {
            String abnormalDetail = buildAbnormalDataNodeDetail(instances);
            if (StringUtils.isNotBlank(abnormalDetail)) {
                dto.setRuntimeStatus(AppStatusEnum.STATUS_ABNORMAL.getStatus());
                dto.setRuntimeStatusLabel(AppStatusEnum.STATUS_ABNORMAL.getInfo());
            } else if (!hasRunningDataNode(instances)) {
                dto.setRuntimeStatus(AppStatusEnum.STATUS_UNKNOWN.getStatus());
                dto.setRuntimeStatusLabel(AppStatusEnum.STATUS_UNKNOWN.getInfo());
            }
            AppDetailVO detail = appStatsCenter.getAppDetail(appId);
            if (detail != null) {
                dto.setMem(detail.getMem());
                dto.setMemUsePercent(detail.getMemUsePercent());
                dto.setHitPercent(detail.getHitPercent());
                dto.setHasOfflineInstances(CollectionUtils.isNotEmpty(detail.getOffLineInstances()));
                if (detail.getAppDesc() != null) {
                    dto.setAppRunDays(detail.getAppDesc().getAppRunDays());
                    if (StringUtils.isNotBlank(detail.getAppDesc().getVersionName())) {
                        dto.setVersionName(detail.getAppDesc().getVersionName());
                    }
                }
            }
        }
        applyPersistedRedisVersion(dto, appDesc);
        return dto;
    }

    private void applyPersistedRedisVersion(AppListItemDto row, AppDesc appDesc) {
        String versionName = appDesc != null ? appDesc.getVersionName() : "";
        if (StringUtils.isNotBlank(versionName)) {
            row.setVersionName(versionName);
        }
    }

    private void applyPersistedRedisVersion(AppDetailDto dto, AppDesc appDesc) {
        String versionName = appDesc != null ? appDesc.getVersionName() : "";
        if (StringUtils.isNotBlank(versionName)) {
            dto.setVersionName(versionName);
        }
    }

    private String buildAbnormalDataNodeDetail(List<InstanceInfo> instances) {
        if (instances == null) return "";
        List<String> nodes = new ArrayList<>();
        for (InstanceInfo instance : instances) {
            if (instance != null && instance.getType() != 5
                    && instance.getStatus() == InstanceStatusEnum.ERROR_STATUS.getStatus()) {
                nodes.add(instance.getHostPort());
            }
        }
        return String.join(", ", nodes);
    }

    private boolean hasRunningDataNode(List<InstanceInfo> instances) {
        if (instances == null || instances.isEmpty()) return false;
        for (InstanceInfo instance : instances) {
            if (instance == null || instance.isOffline() || instance.getType() == 5) continue;
            if (instance.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus()) return true;
        }
        return false;
    }

    private List<AppDetailTabDto> buildDetailTabs(long appId) {
        List<AppDetailTabDto> tabs = new ArrayList<>();
        // 监控统计
        tabs.add(detailTab("app_stat", "集群统计信息"));
        tabs.add(detailTab("app_clientList", "连接信息"));
        tabs.add(detailTab("app_command_analysis", "命令曲线"));
        tabs.add(detailTab("app_latency", "延迟监控"));
        tabs.add(detailTab("app_daily", "日报统计"));
        // 归属本集群的报警记录，与「报警记录」页共用同一接口，仅固定 appId
        tabs.add(detailTab("app_alert_record", "故障报警"));
        // 拓扑节点
        tabs.add(detailTab("app_topology", "节点列表"));
        tabs.add(detailTab("app_top_pic", "集群拓扑"));
        tabs.add(detailTab("app_key_analysis", "键值分析"));
        // 运维诊断
        tabs.add(detailTab("app_ops_topology", "集群拓扑诊断"));
        tabs.add(detailTab("app_ops_config_consistency", "配置一致性校验"));
        tabs.add(detailTab("app_ops_fault", "故障诊断"));
        // 集群密码修改已改为「节点列表」工具栏上的抽屉按钮，不再作为独立 tab 下发
        // 基础信息
        tabs.add(detailTab("app_detail", "集群详情"));
        return tabs;
    }

    private AppDetailTabDto detailTab(String key, String label) {
        AppDetailTabDto tab = new AppDetailTabDto();
        tab.setKey(key);
        tab.setLabel(label);
        return tab;
    }
}
