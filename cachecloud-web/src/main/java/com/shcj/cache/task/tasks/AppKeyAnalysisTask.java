package com.shcj.cache.task.tasks;

import com.alibaba.fastjson.JSONArray;
import com.google.common.collect.Lists;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.constant.AppCheckEnum;
import com.shcj.cache.entity.AppAudit;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.web.enums.BooleanEnum;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.entity.RedisServerNode;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.service.KeyAnalysisStatsStoreService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Tuple;

import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * @author fulei
 */
@Component("AppKeyAnalysisTask")
@Scope(SCOPE_PROTOTYPE)
public class AppKeyAnalysisTask extends BaseTask {

    @Autowired
    private KeyAnalysisStatsStoreService keyAnalysisStatsStoreService;

    private long appId;

    private long auditId;

    private List<RedisServerNode> redisServerNodes;

    private boolean userSelectedNodes;

    private long bigKeyStringBytes;

    private long bigKeyCollectionElements;

    private final static int MAX_ANALYSIS_COUNT = Integer.MAX_VALUE;

    @Override
    public List<String> getTaskSteps() {
        List<String> taskStepList = new ArrayList<String>();
        taskStepList.add(TaskConstants.INIT_METHOD_KEY);
        taskStepList.add("checkAppParam");
        // 一次 SCAN 完成类型/TTL/空闲/大小/Top10（替代原先 5 轮全量扫描 + bigkey）
        taskStepList.add("createRedisServerCombinedKeyAnalysisTask");
        taskStepList.add("waitRedisServerCombinedKeyAnalysisFinish");
        taskStepList.add("showKeyAnalysisResult");
        taskStepList.add("updateAudit");
        return taskStepList;
    }

    @Override
    public TaskFlowStatusEnum init() {
        super.init();

        appId = MapUtils.getLongValue(paramMap, TaskConstants.APPID_KEY);
        if (appId < 0) {
            throw new BizException("task {} appId {} is wrong", taskId, appId);
        }

        auditId = MapUtils.getLongValue(paramMap, TaskConstants.AUDIT_ID_KEY);
        if (auditId < 0) {
            throw new BizException("task {} auditId {} is wrong", taskId, auditId);
        }

        bigKeyStringBytes = MapUtils.getLongValue(paramMap, TaskConstants.BIG_KEY_STRING_BYTES_KEY,
                ConstUtils.DEFAULT_STRING_MAX_LENGTH);
        bigKeyCollectionElements = MapUtils.getLongValue(paramMap, TaskConstants.BIG_KEY_COLLECTION_ELEMENTS_KEY,
                ConstUtils.DEFAULT_HASH_MAX_LENGTH);

        String redisServerNodesStr = MapUtils.getString(paramMap, TaskConstants.REDIS_SERVER_NODES_KEY);
        if (StringUtils.isNotBlank(redisServerNodesStr)) {
            redisServerNodes = JSONArray.parseArray(redisServerNodesStr, RedisServerNode.class);
            if (CollectionUtils.isEmpty(redisServerNodes)) {
                throw new BizException("task {} redisServerNodes is empty", taskId);
            }
            AppAudit appAudit = appAuditDao.getAppAudit(auditId);
            userSelectedNodes = appAudit != null && StringUtils.isNotBlank(appAudit.getParam1());
            logger.info(marker, "user paramMap node: {}, userSelected={}", redisServerNodesStr, userSelectedNodes);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    private void clearKeyAnalysisResultKeys() {
        assistRedisService.del(ConstUtils.getRedisServerTypeKey(appId, auditId));
        assistRedisService.del(ConstUtils.getRedisServerTtlKey(appId, auditId));
        assistRedisService.del(ConstUtils.getRedisServerIdleKey(appId, auditId));
        assistRedisService.del(ConstUtils.getRedisServerValueSizeKey(appId, auditId));
        assistRedisService.del(ConstUtils.getRedisServerTypeMemoryKey(appId, auditId));
        assistRedisService.del(ConstUtils.getRedisServerTopKeyKey(appId, auditId));
        assistRedisService.clearKeyAnalysisRisks(appId, auditId);
        logger.info(marker, "cleared key analysis result keys for appId={} auditId={}", appId, auditId);
    }

    public TaskFlowStatusEnum checkAppParam() {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (appDesc == null) {
            throw new BizException("appId {} appDesc is null", appId);
        }
        if (!appDesc.isOnline()) {
            throw new BizException("appId {} is must be online, ", appId);
        }
        if (!assistRedisService.pingAssistRedis()) {
            throw new BizException("辅助 Redis 不可用: " + assistRedisService.getAssistRedisEndpoint()
                    + "，请检查 cachecloud.redis.main（host/port/password）并重启应用使配置生效");
        }
        String probeKey = ConstUtils.getRedisServerTypeKey(appId, auditId);
        try {
            assistRedisService.zincrby(probeKey, 1, "__probe__");
            if (!assistRedisService.hasZsetData(probeKey)) {
                throw new BizException("辅助 Redis 无法写入键值分析统计 key（" + probeKey + "），请检查 "
                        + assistRedisService.getAssistRedisEndpoint() + " 权限或 WRONGTYPE 脏数据");
            }
            assistRedisService.del(probeKey);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("辅助 Redis 写入探测失败: " + assistRedisService.getAssistRedisEndpoint()
                    + "，" + e.getMessage());
        }
        logger.info(marker, "assist redis ok: {}", assistRedisService.getAssistRedisEndpoint());
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum createRedisServerCombinedKeyAnalysisTask() {
        clearKeyAnalysisResultKeys();
        ensureRedisServerNodesLoaded();
        redisServerNodes = preferAnalysisNodes();
        for (RedisServerNode redisServerNode : redisServerNodes) {
            redisServerNode.setTaskId(null);
        }
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, com.alibaba.fastjson.JSONObject.toJSONString(redisServerNodes));
        logger.info(marker, "appId {} queued {} node(s) for parallel combined analysis", appId, redisServerNodes.size());
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum waitRedisServerCombinedKeyAnalysisFinish() {
        ensureRedisServerNodesLoaded();
        redisServerNodes = preferAnalysisNodes();
        int successCount = 0;
        int failCount = 0;
        List<String> failedNodes = new ArrayList<String>();
        for (RedisServerNode node : redisServerNodes) {
            if (!redisCenter.isRun(appId, node.getIp(), node.getPort())) {
                continue;
            }
            try {
                long childTaskId = taskService.addRedisServerCombinedKeyAnalysisTask(appId, auditId,
                        node.getIp(), node.getPort(), taskId, bigKeyStringBytes, bigKeyCollectionElements);
                node.setTaskId(childTaskId);
            } catch (Exception e) {
                failedNodes.add(node.getIp() + ":" + node.getPort() + "(create failed)");
                failCount++;
                logger.error(marker, "appId {} {}:{} combined analysis create fail", appId, node.getIp(),
                        node.getPort(), e);
            }
        }
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY,
                com.alibaba.fastjson.JSONObject.toJSONString(redisServerNodes));
        for (RedisServerNode redisServerNode : redisServerNodes) {
            String host = redisServerNode.getIp();
            int port = redisServerNode.getPort();

            if (!redisCenter.isRun(appId, host, port)) {
                logger.warn(marker, "appId {} {}:{} is not reachable, skip combined analysis", appId, host, port);
                continue;
            }

            Long childTaskId = redisServerNode.getTaskId();
            if (childTaskId == null || childTaskId <= 0) {
                try {
                    childTaskId = taskService.addRedisServerCombinedKeyAnalysisTask(appId, auditId, host, port, taskId,
                            bigKeyStringBytes, bigKeyCollectionElements);
                    redisServerNode.setTaskId(childTaskId);
                    paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY,
                            com.alibaba.fastjson.JSONObject.toJSONString(redisServerNodes));
                    logger.info(marker, "appId {} {}:{} combined analysis started, childTaskId={}", appId, host, port,
                            childTaskId);
                } catch (Exception e) {
                    failCount++;
                    failedNodes.add(host + ":" + port + "(create failed)");
                    logger.error(marker, "appId {} {}:{} combined analysis create fail", appId, host, port, e);
                    continue;
                }
            }

            TaskFlowStatusEnum taskFlowStatusEnum = waitTaskFinish(childTaskId,
                    TaskConstants.REDIS_SERVER_COMBINED_KEY_ANALYSIS_TIMEOUT);
            if (taskFlowStatusEnum.equals(TaskFlowStatusEnum.ABORT)) {
                failCount++;
                failedNodes.add(host + ":" + port + "(taskId=" + childTaskId + ")");
                logger.error(marker, "appId {} {}:{} combined analysis failed, childTaskId={}", appId, host, port,
                        childTaskId);
            } else {
                successCount++;
                logger.info(marker, "appId {} {}:{} combined analysis succeeded", appId, host, port);
            }
        }
        if (successCount == 0) {
            throw new BizException("appId {} all combined analysis child tasks failed or skipped, nodes: {}", appId,
                    StringUtils.join(failedNodes, ", "));
        }
        if (failCount > 0) {
            String partialMsg = "部分节点分析失败: " + StringUtils.join(failedNodes, ", ");
            logger.warn(marker, "appId {} combined analysis partially failed: {}, succeeded: {}", appId,
                    StringUtils.join(failedNodes, ", "), successCount);
            assistRedisService.appendKeyAnalysisRisk(appId, auditId, partialMsg);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum showKeyAnalysisResult() {
        try {
            if (keyAnalysisStatsStoreService.hasDistributionData(auditId)) {
                assistRedisService.clearKeyAnalysisRisks(appId, auditId);
                appAuditDao.updateParam3(auditId, null);
                logger.info(marker, "appId {} auditId {} key analysis stats available in MySQL", appId, auditId);
                return TaskFlowStatusEnum.SUCCESS;
            }

            assistRedisService.repairKeyAnalysisStatKeys(appId, auditId);
            logZset(ConstUtils.getRedisServerTypeKey(appId, auditId), "type");
            logZset(ConstUtils.getRedisServerTtlKey(appId, auditId), "ttl");
            logZset(ConstUtils.getRedisServerIdleKey(appId, auditId), "idle");
            logZset(ConstUtils.getRedisServerValueSizeKey(appId, auditId), "valueSize");
            logZset(ConstUtils.getRedisServerTypeMemoryKey(appId, auditId), "typeMemory");
            logZset(ConstUtils.getRedisServerTopKeyKey(appId, auditId), "topKey");

            long dbSize = maxNodeDbSize();
            if (dbSize > 0 && !hasKeyAnalysisStatsInAssistRedis()) {
                String message = "Redis 有 key 但分布统计未能汇总到 MySQL。"
                        + "请检查子任务是否执行成功，或查看任务日志后重试";
                List<String> risks = assistRedisService.getKeyAnalysisRisks(appId, auditId);
                if (!risks.isEmpty()) {
                    message += "。子任务：" + StringUtils.join(risks, " | ");
                }
                assistRedisService.appendKeyAnalysisRisk(appId, auditId, message);
                logger.warn(marker, "appId {} auditId {} key analysis stats missing: {}", appId, auditId, message);
            } else {
                List<String> risks = assistRedisService.getKeyAnalysisRisks(appId, auditId);
                if (risks.isEmpty()) {
                    assistRedisService.clearKeyAnalysisRisks(appId, auditId);
                    appAuditDao.updateParam3(auditId, null);
                }
            }
        } catch (Exception e) {
            logger.error(marker, "appId {} auditId {} showKeyAnalysisResult error: {}", appId, auditId, e.getMessage(),
                    e);
            try {
                assistRedisService.appendKeyAnalysisRisk(appId, auditId,
                        "结果汇总异常: " + StringUtils.defaultString(e.getMessage()));
            } catch (Exception ignore) {
                // ignore
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    private void logZset(String redisKey, String label) {
        Set<Tuple> tuples = assistRedisService.zrangeWithScores(redisKey, 0, -1);
        for (Tuple tuple : tuples) {
            logger.info(marker, "{} appId={} {} {} {}", redisKey, appId, label, tuple.getElement(), tuple.getScore());
        }
    }

    /**
     * 未指定节点时优先只扫 slave，降低对 master 的全量 SCAN 压力；无从节点时回退全部实例。
     */
    private List<RedisServerNode> preferAnalysisNodes() {
        List<RedisServerNode> nodes = buildRedisServerNodes();
        if (userSelectedNodes) {
            logger.info(marker, "appId {} use user-selected {} node(s) for analysis", appId, nodes.size());
            return nodes;
        }
        List<RedisServerNode> slaves = new ArrayList<RedisServerNode>();
        for (RedisServerNode node : nodes) {
            if (redisCenter.isMaster(appId, node.getIp(), node.getPort()) == BooleanEnum.FALSE) {
                slaves.add(node);
            }
        }
        if (!slaves.isEmpty()) {
            logger.info(marker, "appId {} use {} slave node(s) for analysis by default", appId, slaves.size());
            return slaves;
        }
        logger.info(marker, "appId {} no slave, use all {} node(s) for analysis", appId, nodes.size());
        return nodes;
    }

    private long maxNodeDbSize() {
        long max = 0;
        for (RedisServerNode node : preferAnalysisNodes()) {
            long dbSize = redisCenter.getDbSize(appId, node.getIp(), node.getPort());
            if (dbSize > max) {
                max = dbSize;
            }
        }
        return max;
    }

    public TaskFlowStatusEnum updateAudit() {
        AppDesc appDesc = appService.getByAppId(appId);
        long totalKeys = maxNodeDbSize();
        appAuditDao.updateParam2(auditId, String.valueOf(totalKeys));
        try {
            keyAnalysisStatsStoreService.finalizeAfterAnalysis(appId, auditId);
            logger.info(marker, "appId {} auditId {} key analysis stats finalized in MySQL", appId, auditId);
        } catch (Exception e) {
            logger.warn(marker, "appId {} auditId {} finalize key analysis stats failed: {}", appId, auditId,
                    e.getMessage());
            assistRedisService.appendKeyAnalysisRisk(appId, auditId,
                    "分布统计未能写入 MySQL：" + e.getMessage());
        }
        if (keyAnalysisStatsStoreService.hasDistributionData(auditId)) {
            assistRedisService.clearKeyAnalysisRisks(appId, auditId);
        }
        persistKeyAnalysisRisksToAudit();
        appAuditDao.updateAppAudit(auditId, AppCheckEnum.APP_PASS.value());
        List<String> risks = assistRedisService.getKeyAnalysisRisks(appId, auditId);
        if (!risks.isEmpty()) {
            logger.warn(marker, "集群({}-{})键值分析完成但有风险, totalKeys={}, risks={}", appDesc.getAppId(),
                    appDesc.getName(), totalKeys, StringUtils.join(risks, " | "));
        } else {
            logger.info(marker, "集群({}-{})键值分析完成, totalKeys={}", appDesc.getAppId(), appDesc.getName(), totalKeys);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    private void persistKeyAnalysisRisksToAudit() {
        List<String> risks = assistRedisService.getKeyAnalysisRisks(appId, auditId);
        if (risks.isEmpty()) {
            appAuditDao.updateParam3(auditId, null);
            return;
        }
        String joined = StringUtils.join(risks, " | ");
        if (joined.length() > 590) {
            joined = joined.substring(0, 587) + "...";
        }
        appAuditDao.updateParam3(auditId, joined);
    }

    private boolean hasKeyAnalysisStatsInAssistRedis() {
        if (assistRedisService.hasZsetData(ConstUtils.getRedisServerTypeKey(appId, auditId))) {
            return true;
        }
        if (assistRedisService.hasZsetData(ConstUtils.getRedisServerTtlKey(appId, auditId))) {
            return true;
        }
        if (assistRedisService.hasZsetData(ConstUtils.getRedisServerIdleKey(appId, auditId))) {
            return true;
        }
        if (assistRedisService.hasZsetData(ConstUtils.getRedisServerValueSizeKey(appId, auditId))) {
            return true;
        }
        if (assistRedisService.hasZsetData(ConstUtils.getRedisServerTypeMemoryKey(appId, auditId))) {
            return true;
        }
        return assistRedisService.hasZsetData(ConstUtils.getRedisServerTopKeyKey(appId, auditId));
    }

    private List<RedisServerNode> transformRedisServerFromInstance(List<InstanceInfo> instanceInfoList, int nodeCount) {
        List<RedisServerNode> redisServerNodes = new ArrayList<RedisServerNode>();
        for (int i = 0; i < instanceInfoList.size() && i < nodeCount; i++) {
            InstanceInfo instanceInfo = instanceInfoList.get(i);
            RedisServerNode redisServerNode = new RedisServerNode();
            redisServerNode.setIp(instanceInfo.getIp());
            redisServerNode.setPort(instanceInfo.getPort());
            redisServerNodes.add(redisServerNode);
        }
        return redisServerNodes;
    }

    private List<RedisServerNode> buildRedisServerNodes() {
        List<RedisServerNode> list = Lists.newArrayList();
        if (CollectionUtils.isEmpty(redisServerNodes)) {
            List<InstanceInfo> instanceInfoList = appService.getAppInstanceInfo(appId).stream()
                    .filter(InstanceInfo::isRedisData)
                    .collect(Collectors.toList());
            if (CollectionUtils.isEmpty(instanceInfoList)) {
                instanceInfoList = appService.getAppMasterInstanceInfoList(appId);
            }
            redisServerNodes = transformRedisServerFromInstance(instanceInfoList, MAX_ANALYSIS_COUNT);
            list.addAll(redisServerNodes);
        } else {
            for (RedisServerNode redisServerNode : redisServerNodes) {
                list.add(new RedisServerNode(redisServerNode.getIp(), redisServerNode.getPort()));
            }
        }
        return list;
    }

    private void ensureRedisServerNodesLoaded() {
        if (CollectionUtils.isNotEmpty(redisServerNodes)) {
            return;
        }
        String redisServerNodesStr = MapUtils.getString(paramMap, TaskConstants.REDIS_SERVER_NODES_KEY);
        if (StringUtils.isBlank(redisServerNodesStr)) {
            throw new BizException("task {} redisServerNodes is empty in paramMap", taskId);
        }
        redisServerNodes = JSONArray.parseArray(redisServerNodesStr, RedisServerNode.class);
        if (CollectionUtils.isEmpty(redisServerNodes)) {
            throw new BizException("task {} redisServerNodes is empty in paramMap", taskId);
        }
    }

}
