package com.shcj.cache.web.service;

import com.shcj.cache.constant.ClusterOperateResult;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.MachineInfo;
import com.shcj.cache.entity.MachineStats;
import com.shcj.cache.machine.MachineCenter;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.redis.RedisDeployCenter;
import com.shcj.cache.stats.instance.InstanceDeployCenter;
import com.shcj.cache.task.tasks.daily.TopologyExamTask;
import com.shcj.cache.util.AppClusterNoSupport;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.enums.RedisOperateEnum;
import com.shcj.cache.web.service.FaultDiagnosticService;
import com.shcj.cache.web.vo.FaultDiagnosticReportVO;
import com.shcj.cache.exception.BizException;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.shcj.cache.web.support.AppRouteIdSupport;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AppOpsApiService {
    private static final long TOPOLOGY_EXAM_CACHE_MS = 30_000L;
    private static final int CONFIG_CHECK_TIMEOUT_MS = 5_000;
    private static final String DATA_NODE_GROUP = "Redis数据节点";
    private static final Set<String> INSTANCE_SPECIFIC_CONFIGS = new LinkedHashSet<>(Arrays.asList(
            "port", "bind", "unixsocket", "unixsocketperm", "pidfile", "logfile", "dir",
            "dbfilename", "cluster-config-file", "cluster-announce-ip", "cluster-announce-port",
            "cluster-announce-bus-port", "replica-announce-ip", "replica-announce-port",
            "slave-announce-ip", "slave-announce-port"));

    private final ConcurrentHashMap<Long, TopologyExamCacheEntry> topologyExamCache = new ConcurrentHashMap<>();

    @Autowired
    private AppRouteIdSupport appRouteIdSupport;

    private long resolveRouteAppId(long idOrClusterNo) {
        return appRouteIdSupport.requireAppId(idOrClusterNo);
    }


    @Autowired
    private AppService appService;

    @Autowired
    private AppDetailTabApiService appDetailTabApiService;

    @Autowired
    private RedisCenter redisCenter;

    @Autowired
    private RedisDeployCenter redisDeployCenter;

    @Autowired
    private MachineCenter machineCenter;

    @Autowired
    private InstanceDeployCenter instanceDeployCenter;

    @Autowired
    private com.shcj.cache.dao.InstanceDao instanceDao;

    @Resource(name = "externalRedisCenter")
    private com.shcj.cache.stats.app.ExternalRedisCenter externalRedisCenter;

    @Autowired
    private TopologyExamTask topologyExamTask;

    @Resource(name = "faultDiagnosticService")
    private FaultDiagnosticService faultDiagnosticService;

    @Value("${cachecloud.web.host-ops-enabled:false}")
    private boolean hostOpsEnabled;

    public AppOpsInstancePageDto getInstanceOps(long appId) {
        appId = resolveRouteAppId(appId);
        AppDesc appDesc = appService.getByAppId(appId);
        AppOpsInstancePageDto dto = new AppOpsInstancePageDto();
        dto.setAppId(appId);
        if (appDesc != null) {
            dto.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
            dto.setAppType(appDesc.getType());
            dto.setAppName(appDesc.getName());
            dto.setSentinelApp(isSentinelApp(appId, appDesc));
            if (TypeUtil.isRedisCluster(appDesc.getType())) {
                dto.setLossSlotsSegmentMap(redisCenter.getClusterLossSlots(appId));
            }
        }
        dto.setHostOpsEnabled(hostOpsEnabled);
        dto.setSshCapable(faultDiagnosticService.isSshCapableForApp(appId));
        dto.setExternalManaged(faultDiagnosticService.isExternalManagedForApp(appId));
        List<MachineStats> appMachines = appService.getAppMachineDetail(appId);
        dto.setManagedMachineCount(appMachines != null ? appMachines.size() : 0);
        Set<String> k8sIps = new java.util.HashSet<>();
        Map<String, MachineInfo> k8sMachineMap = machineCenter.getK8sMachineMap();
        if (k8sMachineMap != null) {
            k8sIps.addAll(k8sMachineMap.keySet());
        }
        dto.setK8sIps(k8sIps);
        dto.getInstances().addAll(appDetailTabApiService.getTopology(appId).getInstances());
        return dto;
    }

    public List<AppOpsMachineItemDto> getAppMachines(long appId) {
        appId = resolveRouteAppId(appId);
        List<MachineStats> machines = appService.getAppMachineDetail(appId);
        List<AppOpsMachineItemDto> items = new ArrayList<>();
        if (machines == null) {
            return items;
        }
        for (MachineStats m : machines) {
            AppOpsMachineItemDto dto = new AppOpsMachineItemDto();
            dto.setIp(m.getIp());
            dto.setCpuUsage(m.getCpuUsage());
            dto.setTraffic(m.getTraffic());
            dto.setLoad(m.getLoad());
            dto.setMemoryAllocated(m.getMemoryAllocated());
            dto.setMachineMemory(m.getMachineMemory());
            dto.setUsedMemory(m.getUsedMemory());
            dto.setMemoryUsageRatio(m.getMemoryUsageRatio());
            if (m.getModifyTime() != null) {
                dto.setModifyTime(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(m.getModifyTime()));
            }
            if (StringUtils.isNotBlank(m.getMemoryTotal())) {
                try {
                    dto.setMemoryTotal(Long.parseLong(m.getMemoryTotal()));
                } catch (NumberFormatException ignore) {
                    // ignore
                }
            }
            if (StringUtils.isNotBlank(m.getMemoryFree())) {
                try {
                    dto.setMemoryFree(Long.parseLong(m.getMemoryFree()));
                } catch (NumberFormatException ignore) {
                    // ignore
                }
            }
            if (dto.getMemoryTotal() > 0 && m.getMemoryAllocated() > 0) {
                double totalGb = dto.getMemoryTotal() / 1024.0 / 1024.0 / 1024.0;
                double allocatedGb = m.getMemoryAllocated() / 1024.0;
                if (totalGb > 0) {
                    dto.setMemoryAllocatedRatio(String.format("%.2f", allocatedGb * 100.0 / totalGb));
                }
            }
            if (m.getInfo() != null) {
                dto.setRealIp(m.getInfo().getRealIp());
                dto.setRoomName(m.getInfo().getRoom());
                dto.setVirtual(m.getInfo().getVirtual() == 1);
            }
            dto.setInstanceCount(m.getInstanceCount());
            items.add(dto);
        }
        return items;
    }

    public AppPasswordDto getPassword(long appId) {
        appId = resolveRouteAppId(appId);
        AppDesc appDesc = appService.getByAppId(appId);
        AppPasswordDto dto = new AppPasswordDto();
        dto.setAppId(appId);
        if (appDesc != null) {
            dto.setAppPassword(appDesc.getAppPassword());
            dto.setCustomPassword(appDesc.getCustomPassword());
        }
        return dto;
    }

    public boolean updatePassword(long appId, String password) {
        appId = resolveRouteAppId(appId);
        if (password == null || password.contains(" ")) {
            throw new IllegalArgumentException("密码语法错误，不能包含空格");
        }
        if (appService.getByAppId(appId) == null) {
            throw new IllegalArgumentException("集群不存在");
        }
        boolean updated = redisDeployCenter.fixPassword(appId, password, true, false);
        return updated && redisDeployCenter.checkAuths(appId);
    }

    public AppConfigConsistencyDto checkConfigConsistency(long appId) {
        appId = resolveRouteAppId(appId);
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            throw new BizException("集群不存在");
        }

        List<InstanceInfo> onlineInstances = appService.getAppOnlineInstanceInfo(appId);
        List<InstanceInfo> comparableInstances = new ArrayList<>();
        if (onlineInstances != null) {
            for (InstanceInfo instance : onlineInstances) {
                if (isComparableRedisInstance(instance)) {
                    comparableInstances.add(instance);
                }
            }
        }
        if (comparableInstances.isEmpty()) {
            throw new BizException("当前集群没有可校验的在线 Redis 实例");
        }

        AppConfigConsistencyDto result = new AppConfigConsistencyDto();
        result.setAppId(appId);
        result.setCheckedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        result.setTotalInstances(comparableInstances.size());

        Map<String, Map<String, String>> configsByNode = new LinkedHashMap<>();
        List<String> dataNodes = new ArrayList<>();

        for (InstanceInfo instance : comparableInstances) {
            AppConfigConsistencyNodeDto node = new AppConfigConsistencyNodeDto();
            node.setInstanceId(instance.getId());
            node.setHostPort(instance.getHostPort());
            node.setGroup(DATA_NODE_GROUP);
            node.setRole(StringUtils.isBlank(instance.getRoleDesc()) ? "未知" : instance.getRoleDesc());
            try {
                Map<String, String> config = loadAllConfigs(appId, instance);
                node.setSuccess(true);
                node.setConfigCount(config.size());
                node.setMessage("校验成功");
                configsByNode.put(instance.getHostPort(), config);
                dataNodes.add(instance.getHostPort());
                result.setCheckedInstances(result.getCheckedInstances() + 1);
            } catch (Exception e) {
                node.setSuccess(false);
                node.setMessage(shortError(e));
                result.setFailedInstances(result.getFailedInstances() + 1);
            }
            result.getNodes().add(node);
        }

        Set<String> encounteredIgnoredConfigs = new TreeSet<>();
        appendConfigComparisons(DATA_NODE_GROUP, dataNodes, configsByNode,
                encounteredIgnoredConfigs, result);

        result.setComparedConfigCount(result.getItems().size());
        result.setIgnoredConfigs(new ArrayList<>(encounteredIgnoredConfigs));
        result.setIgnoredConfigCount(encounteredIgnoredConfigs.size());
        result.setConsistent(result.getFailedInstances() == 0 && result.getInconsistentConfigCount() == 0);
        return result;
    }

    private void appendConfigComparisons(String group, List<String> nodes,
                                         Map<String, Map<String, String>> configsByNode,
                                         Set<String> encounteredIgnoredConfigs,
                                         AppConfigConsistencyDto result) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        Set<String> configNames = new TreeSet<>();
        for (String node : nodes) {
            configNames.addAll(configsByNode.get(node).keySet());
        }
        for (String configName : configNames) {
            if (INSTANCE_SPECIFIC_CONFIGS.contains(configName.toLowerCase())) {
                encounteredIgnoredConfigs.add(configName);
                continue;
            }
            AppConfigConsistencyItemDto item = new AppConfigConsistencyItemDto();
            item.setGroup(group);
            item.setConfigName(configName);
            item.setSensitive(isSensitiveConfig(configName));
            Set<String> rawValues = new LinkedHashSet<>();
            for (String node : nodes) {
                Map<String, String> config = configsByNode.get(node);
                boolean present = config.containsKey(configName);
                String rawValue = present ? config.get(configName) : null;
                rawValues.add(present ? StringUtils.defaultString(rawValue) : "<missing>");
                item.getValues().put(node, displayConfigValue(rawValue, present, item.isSensitive()));
            }
            item.setConsistent(rawValues.size() <= 1);
            if (!item.isConsistent()) {
                result.setInconsistentConfigCount(result.getInconsistentConfigCount() + 1);
            }
            result.getItems().add(item);
        }
    }

    private Map<String, String> loadAllConfigs(long appId, InstanceInfo instance) {
        Jedis jedis = null;
        try {
            jedis = redisCenter.getJedis(appId, instance.getIp(), instance.getPort(),
                    CONFIG_CHECK_TIMEOUT_MS, CONFIG_CHECK_TIMEOUT_MS);
            List<String> configs = jedis.configGet("*");
            Map<String, String> result = new LinkedHashMap<>();
            for (int i = 0; i + 1 < configs.size(); i += 2) {
                result.put(configs.get(i), configs.get(i + 1));
            }
            return result;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    private boolean isComparableRedisInstance(InstanceInfo instance) {
        int type = instance.getType();
        return type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER
                || type == ConstUtils.CACHE_REDIS_STANDALONE;
    }

    private boolean isSensitiveConfig(String configName) {
        String name = StringUtils.defaultString(configName).toLowerCase();
        return name.contains("pass") || name.contains("auth")
                || name.contains("secret") || name.contains("token");
    }

    private String displayConfigValue(String rawValue, boolean present, boolean sensitive) {
        if (!present) {
            return "未配置";
        }
        if (sensitive) {
            return StringUtils.isBlank(rawValue) ? "未设置" : "已设置（已隐藏）";
        }
        return StringUtils.defaultString(rawValue);
    }

    private String shortError(Exception e) {
        String message = e.getMessage();
        if (StringUtils.isBlank(message)) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    public boolean checkPassword(long appId) {
        appId = resolveRouteAppId(appId);
        return redisDeployCenter.checkAuths(appId);
    }

    public TopologyExamDto runTopologyExam(long appId) {
        return runTopologyExam(appId, false);
    }

    public TopologyExamDto runTopologyExam(long appId, boolean refresh) {
        appId = resolveRouteAppId(appId);
        if (!refresh) {
            TopologyExamCacheEntry cached = topologyExamCache.get(appId);
            if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                return cached.dto;
            }
        }
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null || appDesc.getAppId() <= 0) {
            return new TopologyExamDto();
        }
        List<AppDesc> appList = new ArrayList<>();
        appList.add(appDesc);
        TopologyExamDto dto = formatTopologyExam(topologyExamTask.check(appList));
        topologyExamCache.put(appId, new TopologyExamCacheEntry(System.currentTimeMillis() + TOPOLOGY_EXAM_CACHE_MS, dto));
        return dto;
    }

    private static final class TopologyExamCacheEntry {
        private final long expireAt;
        private final TopologyExamDto dto;

        private TopologyExamCacheEntry(long expireAt, TopologyExamDto dto) {
            this.expireAt = expireAt;
            this.dto = dto;
        }
    }

    @SuppressWarnings("unchecked")
    public TopologyExamDto formatTopologyExam(Map<String, Object> raw) {
        TopologyExamDto dto = new TopologyExamDto();
        if (MapUtils.isEmpty(raw)) {
            return dto;
        }

        AppDesc appDesc = (AppDesc) raw.get("appDesc");
        int appType = 0;
        if (appDesc != null) {
            appType = appDesc.getType();
            dto.setAppId(appDesc.getAppId());
            dto.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
            dto.setAppName(appDesc.getName());
            dto.setAppType(appType);
            dto.setTypeDesc(appDesc.getTypeDesc());
        }

        List<Map<String, String>> tipsRaw = (List<Map<String, String>>) raw.get("tips");
        if (tipsRaw != null) {
            for (Map<String, String> tip : tipsRaw) {
                TopologyExamTipDto tipDto = new TopologyExamTipDto();
                tipDto.setAppId(MapUtils.getString(tip, "appId"));
                tipDto.setType(MapUtils.getString(tip, "type"));
                tipDto.setStatus(MapUtils.getString(tip, "status"));
                tipDto.setDesc(MapUtils.getString(tip, "desc"));
                dto.getTips().add(tipDto);
            }
        }
        dto.setIssueCount(dto.getTips().size());
        dto.setOverallOk(dto.getIssueCount() == 0);

        int slaveNum = MapUtils.getIntValue(raw, "slaveNum");
        dto.setSlaveNum(slaveNum);
        dto.setMsFlag(readBooleanFlag(raw.get("msFlag")));
        dto.setSameNetSegment(!isFalseFlag(raw.get("sameNetSegment")));

        Map<InstanceInfo, List<InstanceInfo>> masterSlavesMap =
                (Map<InstanceInfo, List<InstanceInfo>>) raw.get("master_slaves");
        int masterCount = masterSlavesMap != null ? masterSlavesMap.size() : 0;
        dto.setMasterCount(masterCount);
        if (masterSlavesMap != null) {
            for (Map.Entry<InstanceInfo, List<InstanceInfo>> entry : masterSlavesMap.entrySet()) {
                TopologyExamMasterSlaveGroupDto group = new TopologyExamMasterSlaveGroupDto();
                group.setMaster(toInstanceItem(entry.getKey(), raw));
                if (entry.getValue() != null) {
                    for (InstanceInfo slave : entry.getValue()) {
                        group.getSlaves().add(toInstanceItem(slave, raw));
                    }
                }
                group.setSamePhysicalMachine(resolveSamePhysicalMachine(group.getMaster(), group.getSlaves()));
                dto.getMasterSlaves().add(group);
            }
        }

        List<InstanceInfo> sentinels = (List<InstanceInfo>) raw.get("sentinels");
        int sentinelCount = sentinels != null ? sentinels.size() : 0;
        dto.setSentinelCount(sentinelCount);
        if (sentinels != null) {
            for (InstanceInfo sentinel : sentinels) {
                dto.getSentinels().add(toInstanceItem(sentinel, raw));
            }
        }

        Map<String, MachineInfo> machineInfoMap = (Map<String, MachineInfo>) raw.get("machineInfoMap");
        Map<String, List<InstanceInfo>> machineInstancesMap =
                (Map<String, List<InstanceInfo>>) raw.get("machineInstancesMap");
        int machineGroupCount = machineInfoMap != null ? machineInfoMap.size() : 0;
        dto.setMachineGroupCount(machineGroupCount);
        if (machineInfoMap != null) {
            for (Map.Entry<String, MachineInfo> entry : machineInfoMap.entrySet()) {
                TopologyExamMachineGroupDto machine = new TopologyExamMachineGroupDto();
                machine.setRealIp(entry.getKey());
                if (entry.getValue() != null) {
                    machine.setRoom(entry.getValue().getRoom());
                    machine.setRack(entry.getValue().getRack());
                }
                List<InstanceInfo> instList = machineInstancesMap != null
                        ? machineInstancesMap.get(entry.getKey()) : null;
                if (instList != null) {
                    machine.setInstanceCount(instList.size());
                    for (InstanceInfo inst : instList) {
                        machine.getInstances().add(toInstanceItem(inst, raw));
                    }
                }
                dto.getMachines().add(machine);
            }
        }

        Object failoverStatus = raw.get("failoverStatus");
        dto.setFailoverOk(failoverStatus == null ? null : !isFalseFlag(failoverStatus));

        Map<String, Object> slotExamRaw = (Map<String, Object>) raw.get("slotExam");
        if (slotExamRaw != null) {
            dto.setSlotExam(toSlotExam(slotExamRaw));
        }

        boolean clusterOrSentinel = appType == 2 || appType == 5;
        dto.setClusterOrSentinel(clusterOrSentinel);
        if (clusterOrSentinel) {
            boolean diag1Ok = dto.isSameNetSegment();
            boolean diag2Ok = appType == 5
                    ? isSentinelMasterSlaveOk(masterCount, slaveNum, dto.isMsFlag(), sentinelCount)
                    : masterCount > 0 && masterCount == slaveNum && !dto.isMsFlag();
            boolean diag3Ok = machineGroupCount >= 3 && !Boolean.FALSE.equals(dto.getFailoverOk());
            boolean diag4Ok = appType == 2 && dto.getSlotExam() != null && dto.getSlotExam().isOk();
            int diagTotal = appType == 2 ? 4 : 3;
            int diagPass = 0;
            if (diag1Ok) diagPass++;
            if (diag2Ok) diagPass++;
            if (diag3Ok) diagPass++;
            if (appType == 2 && diag4Ok) diagPass++;
            dto.setDiagPass(diagPass);
            dto.setDiagTotal(diagTotal);
            int diagIssueCount = diagTotal - diagPass;
            dto.setIssueCount(Math.max(dto.getTips().size(), diagIssueCount));
            dto.setOverallOk(dto.getTips().isEmpty() && diagIssueCount == 0);

            dto.getDiagnostics().add(diagItem(1, "同网段", diag1Ok,
                    diag1Ok ? "节点均在同网段，网络拓扑正常" : "存在跨网段节点，建议统一网段部署"));
            dto.getDiagnostics().add(diagItem(2, "主从节点", diag2Ok, buildDiag2Conclusion(
                    masterCount, slaveNum, dto.isMsFlag(), appType, sentinelCount, diag2Ok)));
            dto.getDiagnostics().add(diagItem(3, "物理机分布", diag3Ok, buildDiag3Conclusion(
                    machineGroupCount, dto.getFailoverOk(), diag3Ok)));
            if (appType == 2 && dto.getSlotExam() != null) {
                dto.getDiagnostics().add(diagItem(4, "槽位分析", diag4Ok, buildDiag4Conclusion(dto.getSlotExam())));
            }
        }
        return dto;
    }

    private boolean isSentinelMasterSlaveOk(int masterCount, int slaveNum, boolean msFlag, int sentinelCount) {
        return masterCount > 0 && slaveNum >= masterCount && !msFlag && sentinelCount >= 3;
    }

    private TopologyExamDiagItemDto diagItem(int no, String title, boolean ok, String conclusion) {
        TopologyExamDiagItemDto item = new TopologyExamDiagItemDto();
        item.setNo(no);
        item.setTitle(title);
        item.setOk(ok);
        item.setStatusText(ok ? "正常" : "异常");
        item.setConclusion(conclusion);
        return item;
    }

    private String buildDiag2Conclusion(int masterCount, int slaveNum, boolean msFlag,
                                        int appType, int sentinelCount, boolean diag2Ok) {
        if (diag2Ok) {
            if (appType == 5) {
                return "主从拓扑正常，Sentinel 节点不少于 3 个，无同机部署风险";
            }
            return "主从数量匹配，无同机部署风险";
        }
        StringBuilder sb = new StringBuilder();
        if (appType == 5) {
            if (masterCount <= 0) {
                sb.append("未识别到主节点；");
            }
            if (slaveNum < masterCount) {
                sb.append("从节点数量不足；");
            }
        } else {
            if (masterCount <= 0) {
                sb.append("未识别到主节点；");
            } else if (masterCount != slaveNum) {
                sb.append("主从数量不匹配；");
            }
        }
        if (msFlag) {
            sb.append("主从同机部署；");
        }
        if (appType == 5 && sentinelCount < 3) {
            sb.append("Sentinel 少于 3 个");
        }
        if (sb.length() == 0) {
            sb.append("主从拓扑存在异常");
        }
        return sb.toString();
    }

    private String buildDiag3Conclusion(int machineGroupCount, Boolean failoverOk, boolean diag3Ok) {
        if (diag3Ok) {
            return "物理机 " + machineGroupCount + " 组，满足容灾与故障转移要求";
        }
        StringBuilder sb = new StringBuilder();
        if (machineGroupCount < 3) {
            sb.append("物理机分布不足 3 组；");
        }
        if (Boolean.FALSE.equals(failoverOk)) {
            sb.append("主节点物理机过少，单点宕机无法满足故障转移");
        }
        return sb.toString();
    }

    private String buildDiag4Conclusion(TopologyExamSlotDto slotExam) {
        if (!slotExam.isFetchOk()) {
            return "无法获取槽位信息，请检查集群连通性";
        }
        if (slotExam.isSlotComplete() && !slotExam.isImbalance() && !slotExam.isZeroSlotMaster()) {
            return "16384 槽位完整覆盖，分配均衡";
        }
        if (!slotExam.isSlotComplete()) {
            return "槽位未完整覆盖，缺失 " + slotExam.getLostSlotsCount() + " 个";
        }
        return "槽位分配不均，最大/最小比值 " + slotExam.getImbalanceRatio();
    }

    @SuppressWarnings("unchecked")
    private TopologyExamSlotDto toSlotExam(Map<String, Object> slotExamRaw) {
        TopologyExamSlotDto slot = new TopologyExamSlotDto();
        slot.setTotalSlots(MapUtils.getIntValue(slotExamRaw, "totalSlots"));
        slot.setCoveredSlots(MapUtils.getIntValue(slotExamRaw, "coveredSlots"));
        slot.setLostSlotsCount(MapUtils.getIntValue(slotExamRaw, "lostSlotsCount"));
        slot.setFetchOk(MapUtils.getBooleanValue(slotExamRaw, "fetchOk"));
        slot.setSlotComplete(MapUtils.getBooleanValue(slotExamRaw, "slotComplete"));
        slot.setImbalance(MapUtils.getBooleanValue(slotExamRaw, "imbalance"));
        slot.setZeroSlotMaster(MapUtils.getBooleanValue(slotExamRaw, "zeroSlotMaster"));
        slot.setImbalanceRatio(MapUtils.getString(slotExamRaw, "imbalanceRatio"));
        slot.setMinSlotCount(MapUtils.getIntValue(slotExamRaw, "minSlotCount"));
        slot.setMaxSlotCount(MapUtils.getIntValue(slotExamRaw, "maxSlotCount"));
        slot.setMasterCount(MapUtils.getIntValue(slotExamRaw, "masterCount"));
        slot.setOk(MapUtils.getBooleanValue(slotExamRaw, "ok"));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) slotExamRaw.get("distributionRows");
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                TopologyExamSlotRowDto rowDto = new TopologyExamSlotRowDto();
                rowDto.setHostPort(MapUtils.getString(row, "hostPort"));
                rowDto.setSlotRanges(MapUtils.getString(row, "slotRanges"));
                rowDto.setSlotCount(MapUtils.getIntValue(row, "slotCount"));
                rowDto.setPercent(MapUtils.getString(row, "percent"));
                slot.getDistributionRows().add(rowDto);
            }
        }

        List<Map<String, String>> lossRows = (List<Map<String, String>>) slotExamRaw.get("lossRows");
        if (lossRows != null) {
            for (Map<String, String> lossRow : lossRows) {
                TopologyExamSlotLossRowDto lossDto = new TopologyExamSlotLossRowDto();
                lossDto.setHostPort(MapUtils.getString(lossRow, "hostPort"));
                lossDto.setSegments(MapUtils.getString(lossRow, "segments"));
                slot.getLossRows().add(lossDto);
            }
        }
        return slot;
    }

    private String resolveSamePhysicalMachine(TopologyExamInstanceItemDto master,
                                                List<TopologyExamInstanceItemDto> slaves) {
        if (master == null || slaves == null || slaves.isEmpty()) {
            return "no_slave";
        }
        String masterRealIp = master.getRealIp();
        String slaveRealIp = slaves.get(0).getRealIp();
        if (StringUtils.isNotBlank(masterRealIp) && masterRealIp.equals(slaveRealIp)) {
            return "yes";
        }
        return "no";
    }

    @SuppressWarnings("unchecked")
    private TopologyExamInstanceItemDto toInstanceItem(InstanceInfo inst, Map<String, Object> raw) {
        TopologyExamInstanceItemDto item = new TopologyExamInstanceItemDto();
        item.setId(inst.getId());
        item.setIp(inst.getIp());
        item.setPort(inst.getPort());
        item.setHostPort(inst.getHostPort());
        item.setRoleDesc(inst.getRoleDesc());
        Map<String, MachineInfo> instanceInfoMap = (Map<String, MachineInfo>) raw.get("instanceInfoMap");
        if (instanceInfoMap != null) {
            MachineInfo machineInfo = instanceInfoMap.get(inst.getIp());
            if (machineInfo != null) {
                String realIp = StringUtils.isNotBlank(machineInfo.getRealIp())
                        ? machineInfo.getRealIp() : machineInfo.getIp();
                item.setRealIp(realIp);
            }
        }
        if (StringUtils.isBlank(item.getRealIp())) {
            item.setRealIp(inst.getIp());
        }
        return item;
    }

    private boolean readBooleanFlag(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
    }

    private boolean isFalseFlag(Object value) {
        return Boolean.FALSE.equals(value) || "false".equals(String.valueOf(value));
    }

    public FaultDiagnosticReportVO runFaultDiagnostic(long appId, String scenario) {
        appId = resolveRouteAppId(appId);
        String sc = StringUtils.isBlank(scenario) ? FaultDiagnosticService.SCENARIO_REPLICATION_BREAK : scenario;
        return faultDiagnosticService.run(appId, sc);
    }

    public FaultDiagnosticReportVO getFaultReport(String reportId) {
        return faultDiagnosticService.getReport(reportId);
    }

    public List<FaultDiagnosticReportVO> listFaultHistory(long appId, int limit) {
        appId = resolveRouteAppId(appId);
        List<FaultDiagnosticReportVO> list = faultDiagnosticService.listHistory(appId, limit);
        return list != null ? list : Collections.emptyList();
    }

    public OpsActionResultDto clusterDelNode(long appId, int delNodeInstanceId) {
        appId = resolveRouteAppId(appId);
        ClusterOperateResult check = redisDeployCenter.checkClusterForget(appId, delNodeInstanceId);
        if (!check.isSuccess()) {
            return fail(check.getMessage(), check.getStatus());
        }
        InstanceInfo target = instanceDao.getInstanceInfoById(delNodeInstanceId);
        ClusterOperateResult result = redisDeployCenter.delNode(appId, delNodeInstanceId);
        if (result.isSuccess() && target != null) {
            // 与「永久下线」一致：纳管集群要同步摘掉登记清单里的这一行，
            // 否则「补写节点」会把它写回来，且再也加不回同一个节点。
            externalRedisCenter.removeInstanceLine(appId, target.getIp(), target.getPort());
        }
        return toOpsResult(result.isSuccess(), result.getMessage(), result.getStatus());
    }

    public OpsActionResultDto clusterSlaveFailOver(long appId, int slaveInstanceId, String failoverParam) throws Exception {
        appId = resolveRouteAppId(appId);
        boolean success = redisDeployCenter.clusterFailover(appId, slaveInstanceId, failoverParam);
        return toOpsResult(success, success ? "ok" : "failover failed", success ? 1 : 0);
    }

    public OpsActionResultDto addSlave(long appId, int masterInstanceId, String slaveHost) throws Exception {
        appId = resolveRouteAppId(appId);
        if (StringUtils.isBlank(slaveHost) || masterInstanceId <= 0) {
            return fail("参数无效", 0);
        }
        boolean success = redisDeployCenter.addSlave(appId, masterInstanceId, slaveHost);
        return toOpsResult(success, success ? "ok" : "添加从库失败", success ? 1 : 0);
    }

    public OpsActionResultDto sentinelFailOver(long appId, int slaveInstanceId, String failoverParam) throws Exception {
        appId = resolveRouteAppId(appId);
        boolean success;
        if (slaveInstanceId > 0) {
            success = redisDeployCenter.sentinelSlaveFailover(appId, slaveInstanceId, failoverParam);
        } else {
            success = redisDeployCenter.sentinelFailover(appId);
        }
        if (success) {
            success = appService.waitSentinelTopologySync(appId, slaveInstanceId, 90000, 3000);
        }
        return toOpsResult(success, success ? "ok" : "sentinel failover 失败", success ? 1 : 0);
    }

    public OpsActionResultDto addFailSlotsMaster(long appId, int instanceId, String failSlotsMasterHost) throws Exception {
        appId = resolveRouteAppId(appId);
        if (StringUtils.isBlank(failSlotsMasterHost)) {
            return fail("master 地址不能为空", 0);
        }
        RedisOperateEnum result = redisDeployCenter.addSlotsFailMaster(appId, instanceId, failSlotsMasterHost);
        boolean success = result == RedisOperateEnum.OP_SUCCESS || result == RedisOperateEnum.ALREADY_SUCCESS;
        return toOpsResult(success, result.name(), result.getValue());
    }

    public OpsActionResultDto startInstance(long appId, int instanceId) {
        appId = resolveRouteAppId(appId);
        if (instanceId <= 0) {
            return fail("instanceId 无效", 0);
        }
        boolean success = instanceDeployCenter.startExistInstance(appId, instanceId);
        return toOpsResult(success, success ? "开启成功" : "开启失败", success ? 1 : 0);
    }

    public OpsActionResultDto shutdownInstance(long appId, int instanceId) {
        appId = resolveRouteAppId(appId);
        if (instanceId <= 0) {
            return fail("instanceId 无效", 0);
        }
        boolean success = instanceDeployCenter.shutdownExistInstance(appId, instanceId);
        return toOpsResult(success, success ? "关闭成功" : "关闭失败", success ? 1 : 0);
    }

    public OpsActionResultDto forgetInstance(long appId, int instanceId) {
        appId = resolveRouteAppId(appId);
        if (instanceId <= 0) {
            return fail("instanceId 无效", 0);
        }
        InstanceInfo target = instanceDao.getInstanceInfoById(instanceId);
        boolean success = instanceDeployCenter.forgetInstance(appId, instanceId);
        if (success && target != null) {
            // 纳管集群还要把节点从登记清单里摘掉，否则「补写节点」会按旧清单把它写回来
            externalRedisCenter.removeInstanceLine(appId, target.getIp(), target.getPort());
        }
        return toOpsResult(success, success ? "下线成功" : "下线失败", success ? 1 : 0);
    }

    private OpsActionResultDto toOpsResult(boolean success, String message, int status) {
        OpsActionResultDto dto = new OpsActionResultDto();
        dto.setSuccess(success);
        dto.setMessage(message);
        dto.setStatus(status);
        return dto;
    }

    private OpsActionResultDto fail(String message, int status) {
        return toOpsResult(false, message, status);
    }

    private boolean isSentinelApp(long appId, AppDesc appDesc) {
        appId = resolveRouteAppId(appId);
        if (appDesc != null && appDesc.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
            return true;
        }
        for (com.shcj.cache.entity.InstanceInfo instanceInfo : appService.getAppInstanceInfo(appId)) {
            if (instanceInfo.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
                return true;
            }
        }
        return false;
    }
}
