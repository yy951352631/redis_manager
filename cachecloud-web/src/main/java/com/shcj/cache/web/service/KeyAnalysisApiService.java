package com.shcj.cache.web.service;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.constant.AppAuditType;
import com.shcj.cache.constant.AppCheckEnum;
import com.shcj.cache.dao.AppAuditDao;
import com.shcj.cache.dao.AppKeyAnalysisStatsDao;
import com.shcj.cache.dao.InstanceBigKeyDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.task.TaskService;
import com.shcj.cache.task.constant.IdleTimeDistriEnum;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.constant.TtlTimeDistriEnum;
import com.shcj.cache.task.constant.ValueSizeDistriEnum;
import com.shcj.cache.task.entity.InstanceBigKey;
import com.shcj.cache.task.entity.RedisServerNode;
import com.shcj.cache.task.entity.TaskQueue;
import com.shcj.cache.task.entity.TaskStepFlow;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.controller.api.dto.*;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import com.shcj.cache.web.support.AppRouteIdSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import redis.clients.jedis.Tuple;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class KeyAnalysisApiService {
    @Autowired
    private AppRouteIdSupport appRouteIdSupport;

    private long resolveRouteAppId(long idOrClusterNo) {
        return appRouteIdSupport.requireAppId(idOrClusterNo);
    }


    @Autowired
    private AppService appService;

    @Autowired
    private AppAuditDao appAuditDao;

    @Autowired
    private TaskService taskService;

    @Autowired
    private AssistRedisService assistRedisService;

    @Autowired
    private InstanceBigKeyDao instanceBigKeyDao;

    @Autowired
    private RedisCenter redisCenter;

    @Autowired
    private KeyAnalysisStatsStoreService keyAnalysisStatsStoreService;

    @Autowired
    private AppKeyAnalysisStatsDao appKeyAnalysisStatsDao;

    public KeyAnalysisPageDto getPage(long appId) {
        appId = resolveRouteAppId(appId);
        List<AppAudit> appAuditList = appService.getAppAudits(appId, AppAuditType.KEY_ANALYSIS.getValue());
        syncKeyAnalysisAuditList(appAuditList);

        Map<String, InstanceStats> instanceStatsMap = new HashMap<>();
        for (InstanceStats stats : appService.getAppInstanceStats(appId)) {
            instanceStatsMap.put(stats.getIp() + ":" + stats.getPort(), stats);
        }

        KeyAnalysisPageDto dto = new KeyAnalysisPageDto();
        dto.setAppId(appId);
        dto.setRunningTaskId(findRunningKeyAnalysisTaskId(appAuditList));
        dto.setAssistRedisEndpoint(assistRedisService.getAssistRedisEndpoint());

        for (InstanceInfo inst : listRedisInstances(appId)) {
            KeyAnalysisInstanceDto item = new KeyAnalysisInstanceDto();
            item.setId(inst.getId());
            item.setIp(inst.getIp());
            item.setPort(inst.getPort());
            item.setHostPort(inst.getHostPort());
            item.setRoleDesc(inst.getRoleDesc());
            item.setSlave("slave".equals(inst.getRoleDesc()));
            InstanceStats stats = instanceStatsMap.get(inst.getHostPort());
            if (stats != null) {
                item.setCurrItems(stats.getCurrItems());
            }
            dto.getInstances().add(item);
        }

        if (appAuditList != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (AppAudit audit : appAuditList) {
                KeyAnalysisAuditItemDto item = new KeyAnalysisAuditItemDto();
                item.setId(audit.getId());
                item.setStatus(audit.getStatus());
                item.setStatusDesc(audit.getStatusDesc());
                item.setTaskId(audit.getTaskId());
                item.setInfo(audit.getInfo());
                item.setUserName(audit.getUserName());
                item.setRefuseReason(audit.getRefuseReason());
                item.setNodeInfo(audit.getParam1());
                if (StringUtils.isNotBlank(audit.getParam2()) && StringUtils.isNumeric(audit.getParam2().trim())) {
                    item.setTotalKeyCount(Long.parseLong(audit.getParam2().trim()));
                }
                item.setRiskInfo(audit.getParam3());
                item.setRunning(audit.getStatus() == AppCheckEnum.APP_ALLOCATE_RESOURCE.value() && audit.getTaskId() > 0);
                item.setPassed(audit.getStatus() == AppCheckEnum.APP_PASS.value());
                item.setRejected(audit.getStatus() == AppCheckEnum.APP_REJECT.value());
                if (audit.getCreateTime() != null) {
                    item.setCreateTime(sdf.format(audit.getCreateTime()));
                }
                dto.getAudits().add(item);
            }
        }
        return dto;
    }

    public KeyAnalysisStartResultDto start(long appId, AppUser user, String reason, String nodeInfo,
            long bigKeyStringBytes, long bigKeyCollectionElements) {
        appId = resolveRouteAppId(appId);
        if (StringUtils.isBlank(reason)) {
            reason = "管理后台发起";
        }
        long totalKeys = sumDbSizeForNodeInfo(appId, nodeInfo);
        AppDesc appDesc = appService.getByAppId(appId);
        AppAudit appAudit = appService.saveAppKeyAnalysis(appDesc, user, reason, nodeInfo);
        appAuditDao.updateAppAuditOperateUser(appAudit.getId(), user.getId());
        long stringThreshold = bigKeyStringBytes > 0 ? bigKeyStringBytes : ConstUtils.DEFAULT_STRING_MAX_LENGTH;
        long collectionThreshold = bigKeyCollectionElements > 0 ? bigKeyCollectionElements : ConstUtils.DEFAULT_HASH_MAX_LENGTH;
        KeyAnalysisStartResultDto dto = new KeyAnalysisStartResultDto();
        dto.setAuditId(appAudit.getId());
        if (totalKeys == 0) {
            KeyAnalysisStatsSnapshotDto emptySnapshot = new KeyAnalysisStatsSnapshotDto();
            emptySnapshot.setBigKeyStringBytes(stringThreshold);
            emptySnapshot.setBigKeyCollectionElements(collectionThreshold);
            appKeyAnalysisStatsDao.upsertStats(appId, appAudit.getId(), JSON.toJSONString(emptySnapshot));
            appAuditDao.updateParam2(appAudit.getId(), "0");
            appAuditDao.updateAppAudit(appAudit.getId(), AppCheckEnum.APP_PASS.value());
            dto.setTaskId(0);
            return dto;
        }
        long taskId = taskService.addAppKeyAnalysisTask(appId, appAudit.getId(), 0,
                stringThreshold, collectionThreshold);
        dto.setTaskId(taskId);
        return dto;
    }

    @Transactional
    public void deleteRecord(long appId, long auditId) {
        appId = resolveRouteAppId(appId);
        AppAudit audit = appAuditDao.getAppAudit(auditId);
        if (audit == null || audit.getAppId() != appId || audit.getType() != AppAuditType.KEY_ANALYSIS.getValue()) {
            throw new BizException("分析记录不存在");
        }
        if (audit.getStatus() == AppCheckEnum.APP_ALLOCATE_RESOURCE.value()) {
            throw new BizException("执行中的分析记录不能删除");
        }
        appKeyAnalysisStatsDao.deleteByAuditId(auditId);
        instanceBigKeyDao.deleteByAuditId(auditId);
        appAuditDao.deleteById(auditId);
    }

    public KeyAnalysisProgressDto getProgress(long taskId) {
        if (taskId <= 0) {
            throw new BizException("taskId 无效");
        }
        TaskQueue taskQueue = taskService.getTaskQueueById(taskId);
        if (taskQueue == null) {
            throw new BizException("任务不存在");
        }
        List<TaskStepFlow> taskStepFlowList = taskService.getTaskStepFlowList(taskId);
        taskQueue.setTaskStepFlowList(taskStepFlowList);
        TaskStepFlow currentTaskStepFlow = taskService.getCurrentTaskStepFlow(taskId);

        double progress = computeKeyAnalysisProgress(taskQueue, taskStepFlowList);
        KeyAnalysisProgressDto dto = new KeyAnalysisProgressDto();
        dto.setTaskId(taskId);
        dto.setProgress(progress);
        dto.setProgressText(Math.round(progress) + "%");
        dto.setDone(taskQueue.getProgressDoneSteps());
        dto.setTotal(taskQueue.getProgressTotalSteps());
        dto.setStatus(taskQueue.getStatus());
        dto.setFinished(taskQueue.isFinished());
        dto.setCurrentStep(currentTaskStepFlow != null ? currentTaskStepFlow.getStepName() : "");
        return dto;
    }

    public KeyAnalysisResultDto getResult(long appId, long auditId) {
        appId = resolveRouteAppId(appId);
        KeyAnalysisResultDto dto = new KeyAnalysisResultDto();
        dto.setAppId(appId);
        dto.setAuditId(auditId);
        populateKeyAnalysisResult(dto, appId, auditId);
        return dto;
    }

    private void populateKeyAnalysisResult(KeyAnalysisResultDto dto, long appId, long auditId) {
        appId = resolveRouteAppId(appId);
        boolean loadedFromDb = keyAnalysisStatsStoreService.applyPersistedSnapshot(dto, auditId);
        if (!loadedFromDb) {
            loadKeyAnalysisResultFromAssistRedis(dto, appId, auditId);
        }

        int bigKeyTotal = dto.getBigKeyCount();
        if (dto.getBigKeys().isEmpty() || bigKeyTotal <= 0) {
            List<InstanceBigKey> rows = instanceBigKeyDao.getAppBigKeyList(appId, auditId, null);
            bigKeyTotal = rows != null ? rows.size() : 0;
            if (dto.getBigKeys().isEmpty() && rows != null) {
                for (int i = 0; i < Math.min(rows.size(), 100); i++) {
                    InstanceBigKey key = rows.get(i);
                    KeyAnalysisBigKeyDto row = new KeyAnalysisBigKeyDto();
                    row.setInstance(key.getIp() + ":" + key.getPort());
                    row.setKeyName(key.getBigKey());
                    row.setType(key.getType());
                    row.setLength(key.getLength());
                    row.setSizeLabel(KeyAnalysisStatsStoreService.formatBigKeyMetric(key));
                    dto.getBigKeys().add(row);
                }
            }
        }
        dto.setBigKeyCount(bigKeyTotal);

        boolean hasDistributionData = dto.isHasDistributionData()
                || !dto.getIdleKeyDistri().isEmpty()
                || !dto.getKeyTypeDistri().isEmpty()
                || !dto.getKeyTtlDistri().isEmpty()
                || !dto.getKeyValueSizeDistri().isEmpty()
                || !dto.getKeyTypeMemoryDistri().isEmpty()
                || !dto.getTopKeys().isEmpty()
                || bigKeyTotal > 0;
        long totalKeyCount = resolveTotalKeyCount(appId, auditId);
        List<String> analysisRisks = resolveKeyAnalysisRisks(appId, auditId, hasDistributionData);
        if (!analysisRisks.isEmpty()) {
            dto.getAnalysisRisks().clear();
            dto.getAnalysisRisks().addAll(analysisRisks);
        }
        boolean hasAnalysisRisk = !dto.getAnalysisRisks().isEmpty();
        dto.setHasDistributionData(hasDistributionData);
        dto.setEmptyDb(totalKeyCount == 0);
        dto.setStatsMissing(totalKeyCount > 0 && !hasDistributionData && !hasAnalysisRisk);
        dto.setHasAnalysisRisk(hasAnalysisRisk);
        if (!dto.isHasTypeMemoryData()) {
            dto.setHasTypeMemoryData(hasTypeMemoryScanData(appId, auditId));
        }
        if (!dto.isTopKeyFromMemoryScan()) {
            dto.setTopKeyFromMemoryScan(hasTopKeyMemoryScanData(appId, auditId));
        }
        if (StringUtils.isBlank(dto.getAssistRedisEndpoint())) {
            dto.setAssistRedisEndpoint(assistRedisService.getAssistRedisEndpoint());
        }
        if (dto.getBigKeyStringBytes() <= 0) dto.setBigKeyStringBytes(ConstUtils.DEFAULT_STRING_MAX_LENGTH);
        if (dto.getBigKeyCollectionElements() <= 0) dto.setBigKeyCollectionElements(ConstUtils.DEFAULT_HASH_MAX_LENGTH);
        dto.setTotalKeyCount(totalKeyCount);
        String nodes = resolveKeyAnalysisNodes(appId, auditId);
        if (StringUtils.isNotBlank(nodes)) {
            dto.getAnalysisNodes().add(nodes);
        }
    }

    private void loadKeyAnalysisResultFromAssistRedis(KeyAnalysisResultDto dto, long appId, long auditId) {
        String idleKeyResultKey = ConstUtils.getRedisServerIdleKey(appId, auditId);
        Set<Tuple> idleKeyTuples = assistRedisService.zrangeWithScores(idleKeyResultKey, 0, -1);
        for (Tuple tuple : idleKeyTuples) {
            IdleTimeDistriEnum idleTimeDistriEnum = IdleTimeDistriEnum.getByValue(tuple.getElement());
            if (idleTimeDistriEnum == null) {
                continue;
            }
            dto.getIdleKeyDistri().add(new ParamCountDto(idleTimeDistriEnum.getInfo(), tuple.getScore(), ""));
        }

        String keyTypeResultKey = ConstUtils.getRedisServerTypeKey(appId, auditId);
        for (Tuple tuple : assistRedisService.zrangeWithScores(keyTypeResultKey, 0, -1)) {
            dto.getKeyTypeDistri().add(new ParamCountDto(tuple.getElement(), tuple.getScore(), ""));
        }

        String keyTtlResultKey = ConstUtils.getRedisServerTtlKey(appId, auditId);
        for (Tuple tuple : assistRedisService.zrangeWithScores(keyTtlResultKey, 0, -1)) {
            TtlTimeDistriEnum ttlTimeDistriEnum = TtlTimeDistriEnum.getByValue(tuple.getElement());
            if (ttlTimeDistriEnum == null) {
                continue;
            }
            dto.getKeyTtlDistri().add(new ParamCountDto(ttlTimeDistriEnum.getInfo(), tuple.getScore(), ""));
        }

        String keyValueSizeResultKey = ConstUtils.getRedisServerValueSizeKey(appId, auditId);
        for (Tuple tuple : assistRedisService.zrangeWithScores(keyValueSizeResultKey, 0, -1)) {
            ValueSizeDistriEnum valueSizeDistriEnum = ValueSizeDistriEnum.getByValue(tuple.getElement());
            if (valueSizeDistriEnum == null) {
                continue;
            }
            dto.getKeyValueSizeDistri().add(new ParamCountDto(valueSizeDistriEnum.getInfo(), tuple.getScore(), ""));
        }

        String keyTypeMemoryResultKey = ConstUtils.getRedisServerTypeMemoryKey(appId, auditId);
        for (Tuple tuple : assistRedisService.zrangeWithScores(keyTypeMemoryResultKey, 0, -1)) {
            dto.getKeyTypeMemoryDistri().add(new ParamCountDto(tuple.getElement(), tuple.getScore(), ""));
        }

        boolean topKeyFromMemoryScan = hasTopKeyMemoryScanData(appId, auditId);
        List<InstanceBigKey> topKeyList = resolveTopKeyList(appId, auditId);
        for (int i = 0; i < topKeyList.size(); i++) {
            InstanceBigKey key = topKeyList.get(i);
            KeyAnalysisBigKeyDto row = new KeyAnalysisBigKeyDto();
            row.setInstance(key.getIp() + ":" + key.getPort());
            row.setKeyName(key.getBigKey());
            row.setType(key.getType());
            row.setLength(key.getLength());
            row.setSizeLabel(topKeyFromMemoryScan ? formatMemoryBytes(key.getLength()) : key.getLengthFormat());
            dto.getTopKeys().add(row);
        }

        List<InstanceBigKey> instanceBigKeyList = instanceBigKeyDao.getAppBigKeyList(appId, auditId, null);
        if (instanceBigKeyList != null) {
            int limit = Math.min(instanceBigKeyList.size(), 100);
            for (int i = 0; i < limit; i++) {
                InstanceBigKey key = instanceBigKeyList.get(i);
                KeyAnalysisBigKeyDto row = new KeyAnalysisBigKeyDto();
                row.setInstance(key.getIp() + ":" + key.getPort());
                row.setKeyName(key.getBigKey());
                row.setType(key.getType());
                row.setLength(key.getLength());
                row.setSizeLabel(KeyAnalysisStatsStoreService.formatBigKeyMetric(key));
                dto.getBigKeys().add(row);
            }
        }

        int bigKeyTotal = instanceBigKeyList != null ? instanceBigKeyList.size() : 0;
        dto.setBigKeyCount(bigKeyTotal);
        dto.setTopKeyFromMemoryScan(topKeyFromMemoryScan);
        dto.setHasTypeMemoryData(hasTypeMemoryScanData(appId, auditId));
        dto.setHasDistributionData(!dto.getIdleKeyDistri().isEmpty()
                || !dto.getKeyTypeDistri().isEmpty()
                || !dto.getKeyTtlDistri().isEmpty()
                || !dto.getKeyValueSizeDistri().isEmpty()
                || !dto.getKeyTypeMemoryDistri().isEmpty()
                || !dto.getTopKeys().isEmpty()
                || bigKeyTotal > 0);
        dto.setAssistRedisEndpoint(assistRedisService.getAssistRedisEndpoint());
    }

    private Long findRunningKeyAnalysisTaskId(List<AppAudit> appAuditList) {
        if (appAuditList == null) {
            return null;
        }
        for (AppAudit appAudit : appAuditList) {
            if (appAudit.getStatus() == AppCheckEnum.APP_ALLOCATE_RESOURCE.value() && appAudit.getTaskId() > 0) {
                return appAudit.getTaskId();
            }
        }
        return null;
    }

    private List<InstanceInfo> listRedisInstances(long appId) {
        appId = resolveRouteAppId(appId);
        List<InstanceInfo> instances = appService.getAppInstanceInfo(appId);
        if (instances == null) {
            return new ArrayList<>();
        }
        return instances.stream()
                .filter(instance -> !instance.isOffline())
                .filter(InstanceInfo::isRedisData)
                .collect(Collectors.toList());
    }

    private void syncKeyAnalysisAuditList(List<AppAudit> appAuditList) {
        if (appAuditList == null || appAuditList.isEmpty()) {
            return;
        }
        for (AppAudit appAudit : appAuditList) {
            if (appAudit.getStatus() != AppCheckEnum.APP_ALLOCATE_RESOURCE.value() || appAudit.getTaskId() <= 0) {
                continue;
            }
            TaskQueue taskQueue = taskService.getTaskQueueById(appAudit.getTaskId());
            if (taskQueue == null) {
                continue;
            }
            if (taskQueue.isAbort()) {
                appAuditDao.updateAppAudit(appAudit.getId(), AppCheckEnum.APP_REJECT.value());
                if (StringUtils.isBlank(appAudit.getRefuseReason())) {
                    appAuditDao.updateRefuseReason(appAudit.getId(), "分析任务失败");
                    appAudit.setRefuseReason("分析任务失败");
                }
                appAudit.setStatus(AppCheckEnum.APP_REJECT.value());
            } else if (taskQueue.isSuccess()) {
                appAuditDao.updateAppAudit(appAudit.getId(), AppCheckEnum.APP_PASS.value());
                appAuditDao.updateRefuseReason(appAudit.getId(), "");
                appAudit.setStatus(AppCheckEnum.APP_PASS.value());
                appAudit.setRefuseReason(null);
            }
        }
    }

    private double computeKeyAnalysisProgress(TaskQueue taskQueue, List<TaskStepFlow> taskStepFlowList) {
        if (taskQueue.isSuccess() || taskQueue.isAbort()) {
            return 100;
        }
        if (taskStepFlowList == null || taskStepFlowList.isEmpty()) {
            return 0;
        }
        final double setupRatio = 0.15;
        final double scanRatio = 0.80;
        final double finalRatio = 0.05;

        int setupDone = 0;
        TaskStepFlow waitStep = null;
        int finalDone = 0;
        for (TaskStepFlow step : taskStepFlowList) {
            String stepName = step.getStepName();
            if ("waitRedisServerCombinedKeyAnalysisFinish".equals(stepName)) {
                waitStep = step;
            } else if ("showKeyAnalysisResult".equals(stepName) || "updateAudit".equals(stepName)) {
                if (step.isSuccess() || step.isSkip()) {
                    finalDone++;
                }
            } else if ("init".equals(stepName) || "checkAppParam".equals(stepName)
                    || "createRedisServerCombinedKeyAnalysisTask".equals(stepName)) {
                if (step.isSuccess() || step.isSkip()) {
                    setupDone++;
                }
            }
        }
        int setupTotal = 3;
        double progress = setupRatio * 100 * setupDone / setupTotal;

        if (waitStep != null) {
            if (waitStep.isSuccess() || waitStep.isSkip()) {
                progress += scanRatio * 100;
            } else if (waitStep.getStatus() == TaskFlowStatusEnum.RUNNING.getStatus()) {
                progress += scanRatio * averageChildKeyAnalysisProgress(taskQueue);
            }
        }

        progress += finalRatio * 100 * finalDone / 2;
        return Math.min(99, Math.max(0, progress));
    }

    private double averageChildKeyAnalysisProgress(TaskQueue parentTask) {
        Map<String, Object> paramMap = parentTask.getParamMap();
        if (paramMap == null) {
            return 0;
        }
        String nodesStr = MapUtils.getString(paramMap, TaskConstants.REDIS_SERVER_NODES_KEY);
        if (StringUtils.isBlank(nodesStr)) {
            return 0;
        }
        List<RedisServerNode> nodes = JSON.parseArray(nodesStr, RedisServerNode.class);
        if (nodes == null || nodes.isEmpty()) {
            return 0;
        }
        double sum = 0;
        int count = 0;
        for (RedisServerNode node : nodes) {
            Long childTaskId = node.getTaskId();
            if (childTaskId == null || childTaskId <= 0) {
                continue;
            }
            TaskQueue child = taskService.getTaskQueueById(childTaskId);
            if (child == null) {
                continue;
            }
            List<TaskStepFlow> childSteps = taskService.getTaskStepFlowList(childTaskId);
            child.setTaskStepFlowList(childSteps);
            if (child.isSuccess()) {
                sum += 100;
                count++;
                continue;
            }
            if (child.isAbort()) {
                count++;
                continue;
            }
            double childValue = child.getProgressValue();
            if (childValue >= 0) {
                sum += childValue;
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    private List<InstanceBigKey> resolveTopKeyList(long appId, long auditId) {
        appId = resolveRouteAppId(appId);
        String topKeyRedisKey = ConstUtils.getRedisServerTopKeyKey(appId, auditId);
        List<InstanceBigKey> fromZset = loadTopKeysFromZset(topKeyRedisKey);
        if (!fromZset.isEmpty()) {
            return fromZset;
        }
        String topKeyJson = assistRedisService.getWithNoSerialize(topKeyRedisKey);
        if (StringUtils.isNotBlank(topKeyJson) && topKeyJson.trim().startsWith("[")) {
            try {
                List<InstanceBigKey> parsed = JSON.parseArray(topKeyJson, InstanceBigKey.class);
                if (parsed != null && !parsed.isEmpty()) {
                    parsed.sort((a, b) -> Long.compare(b.getLength(), a.getLength()));
                    return new ArrayList<>(parsed.subList(0, Math.min(10, parsed.size())));
                }
            } catch (Exception ignored) {
                // ignore legacy corrupt cache
            }
        }
        List<InstanceBigKey> fallback = instanceBigKeyDao.getTopAppBigKeyList(appId, auditId, 10);
        return fallback != null ? fallback : new ArrayList<>();
    }

    private List<InstanceBigKey> loadTopKeysFromZset(String topKeyRedisKey) {
        List<InstanceBigKey> list = new ArrayList<>();
        Set<Tuple> tuples = assistRedisService.zrevrangeWithScores(topKeyRedisKey, 0, 9);
        if (tuples == null || tuples.isEmpty()) {
            return list;
        }
        for (Tuple tuple : tuples) {
            String[] parts = ConstUtils.decodeTopKeyMember(tuple.getElement());
            if (parts == null) {
                continue;
            }
            InstanceBigKey key = new InstanceBigKey();
            key.setIp(parts[0]);
            key.setPort(NumberUtils.toInt(parts[1]));
            key.setType(parts[2]);
            key.setBigKey(parts[3]);
            key.setLength((long) tuple.getScore());
            list.add(key);
        }
        return list;
    }

    private boolean hasTypeMemoryScanData(long appId, long auditId) {
        appId = resolveRouteAppId(appId);
        String keyTypeMemoryResultKey = ConstUtils.getRedisServerTypeMemoryKey(appId, auditId);
        Set<Tuple> tuples = assistRedisService.zrangeWithScores(keyTypeMemoryResultKey, 0, -1);
        return tuples != null && !tuples.isEmpty();
    }

    private boolean hasTopKeyMemoryScanData(long appId, long auditId) {
        appId = resolveRouteAppId(appId);
        String topKeyRedisKey = ConstUtils.getRedisServerTopKeyKey(appId, auditId);
        Set<Tuple> tuples = assistRedisService.zrevrangeWithScores(topKeyRedisKey, 0, 0);
        if (tuples != null && !tuples.isEmpty()) {
            return true;
        }
        return StringUtils.isNotBlank(assistRedisService.getWithNoSerialize(topKeyRedisKey));
    }

    private List<String> resolveKeyAnalysisRisks(long appId, long auditId, boolean hasDistributionData) {
        appId = resolveRouteAppId(appId);
        List<String> risks = new ArrayList<>(assistRedisService.getKeyAnalysisRisks(appId, auditId));
        if (hasDistributionData) {
            clearStaleKeyAnalysisParam3(auditId);
            risks.removeIf(this::isAssistRedisStorageRisk);
            return risks;
        }
        if (!risks.isEmpty()) {
            return risks;
        }
        AppAudit audit = appAuditDao.getAppAudit(auditId);
        if (audit == null || StringUtils.isBlank(audit.getParam3())) {
            return risks;
        }
        for (String part : audit.getParam3().split(" \\| ")) {
            if (StringUtils.isNotBlank(part)) {
                risks.add(part.trim());
            }
        }
        return risks;
    }

    private boolean isAssistRedisStorageRisk(String message) {
        if (StringUtils.isBlank(message)) {
            return false;
        }
        return message.contains("辅助 Redis")
                || message.contains("cachecloud.redis.main")
                || message.contains("cc:key:")
                || message.contains("分布统计未写入")
                || message.contains("未能写入辅助 Redis")
                || message.contains("未能汇总到 MySQL");
    }

    private void clearStaleKeyAnalysisParam3(long auditId) {
        AppAudit audit = appAuditDao.getAppAudit(auditId);
        if (audit != null && StringUtils.isNotBlank(audit.getParam3())) {
            appAuditDao.updateParam3(auditId, null);
        }
    }

    private long resolveTotalKeyCount(long appId, long auditId) {
        appId = resolveRouteAppId(appId);
        List<AppAudit> audits = appService.getAppAudits(appId, AppAuditType.KEY_ANALYSIS.getValue());
        if (audits != null) {
            for (AppAudit audit : audits) {
                if (audit.getId() == auditId && StringUtils.isNotBlank(audit.getParam2())
                        && StringUtils.isNumeric(audit.getParam2().trim())) {
                    return Long.parseLong(audit.getParam2().trim());
                }
            }
        }
        return -1;
    }

    private long sumDbSizeForNodeInfo(long appId, String nodeInfo) {
        appId = resolveRouteAppId(appId);
        long total = 0;
        if (StringUtils.isNotBlank(nodeInfo)) {
            for (String node : nodeInfo.split(",")) {
                String[] parts = node.trim().split(":");
                if (parts.length != 2) {
                    continue;
                }
                long dbSize = redisCenter.getDbSize(appId, parts[0].trim(), NumberUtils.toInt(parts[1].trim()));
                if (dbSize > 0) {
                    total += dbSize;
                }
            }
            return total;
        }
        return sumDbSizeForDefaultScope(appId);
    }

    private long sumDbSizeForDefaultScope(long appId) {
        appId = resolveRouteAppId(appId);
        long total = 0;
        for (InstanceInfo instance : appService.listDefaultKeyAnalysisInstances(appId)) {
            long dbSize = redisCenter.getDbSize(appId, instance.getIp(), instance.getPort());
            if (dbSize > 0) {
                total += dbSize;
            }
        }
        return total;
    }

    private String resolveKeyAnalysisNodes(long appId, long auditId) {
        appId = resolveRouteAppId(appId);
        List<AppAudit> audits = appService.getAppAudits(appId, AppAuditType.KEY_ANALYSIS.getValue());
        if (audits == null) {
            return null;
        }
        AppAudit target = null;
        for (AppAudit audit : audits) {
            if (audit.getId() == auditId) {
                target = audit;
                break;
            }
        }
        if (target != null && StringUtils.isNotBlank(target.getParam1())) {
            return target.getParam1();
        }
        if (target == null || target.getTaskId() <= 0) {
            return null;
        }
        TaskQueue taskQueue = taskService.getTaskQueueById(target.getTaskId());
        if (taskQueue == null) {
            return null;
        }
        String nodesStr = MapUtils.getString(taskQueue.getParamMap(), TaskConstants.REDIS_SERVER_NODES_KEY);
        if (StringUtils.isBlank(nodesStr)) {
            return taskQueue.getImportantInfo();
        }
        List<RedisServerNode> nodes = JSON.parseArray(nodesStr, RedisServerNode.class);
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(nodes.get(i).getIp()).append(":").append(nodes.get(i).getPort());
        }
        return sb.toString();
    }

    private static String formatMemoryBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
