package com.shcj.cache.task.tasks.diagnosticTask;

import com.shcj.cache.constant.DiagnosticTypeEnum;
import com.shcj.cache.entity.DiagnosticTaskRecord;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.StringUtil;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * Key cleanup is deliberately split into preview and confirmed deletion.
 */
@Component("InstanceDelKeyTask")
@Scope(SCOPE_PROTOTYPE)
public class InstanceDelKeyTask extends BaseTask {

    private static final int SCAN_COUNT = 200;
    private static final int UNLINK_BATCH_SIZE = 200;
    private static final int DELETE_STATUS_NO_MATCH = 0;
    private static final int DELETE_STATUS_DELETED = 1;
    private static final int DELETE_STATUS_NOT_DELETED = 2;
    private static final String CONDITION_TEMPLATE = "pattern:{0}";

    private String host;
    private int port;
    private long appId;
    private String pattern;
    private long auditId;
    private long parentTaskId;
    private boolean confirmDelete;
    private long previewRecordId;
    private String previewRedisKey;

    @Override
    public List<String> getTaskSteps() {
        List<String> taskStepList = new ArrayList<>();
        taskStepList.add(TaskConstants.INIT_METHOD_KEY);
        taskStepList.add("checkIsRun");
        taskStepList.add("delKey");
        return taskStepList;
    }

    @Override
    public TaskStepFlowEnum.TaskFlowStatusEnum init() {
        super.init();
        appId = MapUtils.getLongValue(paramMap, TaskConstants.APPID_KEY);
        auditId = MapUtils.getLongValue(paramMap, TaskConstants.AUDIT_ID_KEY);
        host = MapUtils.getString(paramMap, TaskConstants.HOST_KEY);
        port = MapUtils.getIntValue(paramMap, TaskConstants.PORT_KEY);
        pattern = MapUtils.getString(paramMap, "pattern");
        parentTaskId = MapUtils.getLongValue(paramMap, "parentTaskId");
        confirmDelete = MapUtils.getBooleanValue(paramMap, "confirmDelete", false);
        previewRecordId = MapUtils.getLongValue(paramMap, "previewRecordId");
        previewRedisKey = MapUtils.getString(paramMap, "previewRedisKey");

        if (appId <= 0 || auditId <= 0 || StringUtils.isBlank(host) || port <= 0) {
            logger.error(marker, "task {} has invalid cleanup parameters", taskId);
            return TaskStepFlowEnum.TaskFlowStatusEnum.ABORT;
        }
        if (confirmDelete && (previewRecordId <= 0 || StringUtils.isBlank(previewRedisKey))) {
            logger.error(marker, "task {} has invalid preview reference", taskId);
            return TaskStepFlowEnum.TaskFlowStatusEnum.ABORT;
        }
        return TaskStepFlowEnum.TaskFlowStatusEnum.SUCCESS;
    }

    public TaskStepFlowEnum.TaskFlowStatusEnum checkIsRun() {
        if (!redisCenter.isRun(appId, host, port)) {
            logger.error(marker, "{} {}:{} is not run", appId, host, port);
            if (confirmDelete) {
                diagnosticTaskRecordDao.updateDeleteFinished(previewRecordId,
                        DELETE_STATUS_NOT_DELETED, getPreviewCount(), 2, 0);
            }
            return TaskStepFlowEnum.TaskFlowStatusEnum.ABORT;
        }
        return TaskStepFlowEnum.TaskFlowStatusEnum.SUCCESS;
    }

    public TaskStepFlowEnum.TaskFlowStatusEnum delKey() {
        return confirmDelete ? deletePreviewKeys() : previewKeys();
    }

    private TaskStepFlowEnum.TaskFlowStatusEnum previewKeys() {
        DiagnosticTaskRecord record = new DiagnosticTaskRecord();
        record.setAppId(appId);
        record.setAuditId(auditId);
        String hostPort = host + ":" + port;
        record.setNode(hostPort);
        record.setDiagnosticCondition(MessageFormat.format(CONDITION_TEMPLATE, pattern));
        record.setTaskId(taskId);
        record.setParentTaskId(parentTaskId);
        record.setType(DiagnosticTypeEnum.DEL_KEY.getType());
        record.setStatus(0);
        record.setParam1(String.valueOf(DELETE_STATUS_NOT_DELETED));
        record.setParam2("0");
        diagnosticTaskRecordDao.insertDiagnosticTaskRecord(record);

        long recordId = record.getId();
        long startTime = System.currentTimeMillis();
        String resultKey = ConstUtils.getInstanceDelKey(taskId, hostPort);
        Jedis jedis = null;
        try {
            jedis = redisCenter.getJedis(appId, host, port);
            assistRedisService.del(resultKey);
            if (jedis.dbSize() == 0) {
                diagnosticTaskRecordDao.updateDeletePreview(recordId, "", DELETE_STATUS_NO_MATCH,
                        0, 1, System.currentTimeMillis() - startTime);
                return TaskStepFlowEnum.TaskFlowStatusEnum.SUCCESS;
            }

            byte[] cursor = "0".getBytes(StandardCharsets.UTF_8);
            ScanParams scanParams = StringUtil.isBlank(pattern)
                    ? new ScanParams().count(SCAN_COUNT)
                    : new ScanParams().match(pattern).count(SCAN_COUNT);
            long matchedCount = 0;
            do {
                ScanResult<byte[]> scanResult = jedis.scan(cursor, scanParams);
                cursor = scanResult.getCursorAsBytes();
                List<String> matchedKeys = new ArrayList<>(scanResult.getResult().size());
                for (byte[] key : scanResult.getResult()) {
                    matchedKeys.add(new String(key, StandardCharsets.UTF_8));
                }
                if (!matchedKeys.isEmpty()) {
                    if (!assistRedisService.rpushList(resultKey, matchedKeys)) {
                        throw new IllegalStateException("save cleanup preview failed");
                    }
                    matchedCount += matchedKeys.size();
                }
                TimeUnit.MILLISECONDS.sleep(5);
            } while (!Arrays.equals("0".getBytes(StandardCharsets.UTF_8), cursor));

            int deleteStatus = matchedCount == 0 ? DELETE_STATUS_NO_MATCH : DELETE_STATUS_NOT_DELETED;
            String storedResultKey = matchedCount == 0 ? "" : resultKey;
            diagnosticTaskRecordDao.updateDeletePreview(recordId, storedResultKey, deleteStatus,
                    matchedCount, 1, System.currentTimeMillis() - startTime);
            logger.info(marker, "{} {}:{} preview key completed, matched {}", appId, host, port, matchedCount);
            return TaskStepFlowEnum.TaskFlowStatusEnum.SUCCESS;
        } catch (Exception e) {
            logger.error(marker, "preview delete key appId {} {}:{} failed", appId, host, port, e);
            assistRedisService.del(resultKey);
            diagnosticTaskRecordDao.updateDeletePreview(recordId, "", DELETE_STATUS_NOT_DELETED,
                    0, 2, System.currentTimeMillis() - startTime);
            return TaskStepFlowEnum.TaskFlowStatusEnum.ABORT;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    private TaskStepFlowEnum.TaskFlowStatusEnum deletePreviewKeys() {
        long startTime = System.currentTimeMillis();
        Long total = assistRedisService.llen(previewRedisKey);
        if (total == null || total <= 0) {
            logger.error(marker, "cleanup preview {} is empty or expired", previewRedisKey);
            diagnosticTaskRecordDao.updateDeleteFinished(previewRecordId,
                    DELETE_STATUS_NOT_DELETED, 0, 2, System.currentTimeMillis() - startTime);
            return TaskStepFlowEnum.TaskFlowStatusEnum.ABORT;
        }

        Jedis jedis = null;
        long deletedCount = 0;
        try {
            jedis = redisCenter.getJedis(appId, host, port);
            for (int offset = 0; offset < total; offset += UNLINK_BATCH_SIZE) {
                int end = (int) Math.min(offset + UNLINK_BATCH_SIZE - 1L, total - 1L);
                List<String> keys = assistRedisService.lrange(previewRedisKey, offset, end);
                if (keys == null || keys.isEmpty()) {
                    throw new IllegalStateException("cleanup preview data is incomplete");
                }
                Pipeline pipeline = jedis.pipelined();
                for (String key : keys) {
                    pipeline.unlink(key);
                }
                List<Object> results = pipeline.syncAndReturnAll();
                for (Object result : results) {
                    if (result instanceof Number) {
                        deletedCount += ((Number) result).longValue();
                    }
                }
                TimeUnit.MILLISECONDS.sleep(5);
            }
            diagnosticTaskRecordDao.updateDeleteFinished(previewRecordId,
                    DELETE_STATUS_DELETED, deletedCount, 1, System.currentTimeMillis() - startTime);
            logger.info(marker, "{} {}:{} unlink preview completed, deleted {} of {}",
                    appId, host, port, deletedCount, total);
            return TaskStepFlowEnum.TaskFlowStatusEnum.SUCCESS;
        } catch (Exception e) {
            logger.error(marker, "confirm delete key appId {} {}:{} failed", appId, host, port, e);
            diagnosticTaskRecordDao.updateDeleteFinished(previewRecordId,
                    DELETE_STATUS_NOT_DELETED, total, 2, System.currentTimeMillis() - startTime);
            return TaskStepFlowEnum.TaskFlowStatusEnum.ABORT;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    private long getPreviewCount() {
        DiagnosticTaskRecord record = diagnosticTaskRecordDao.getById(previewRecordId);
        if (record == null || StringUtils.isBlank(record.getParam2())) {
            return 0;
        }
        try {
            return Long.parseLong(record.getParam2());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
