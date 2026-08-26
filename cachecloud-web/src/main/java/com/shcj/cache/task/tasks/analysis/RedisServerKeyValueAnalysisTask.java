package com.shcj.cache.task.tasks.analysis;

import com.google.common.util.concurrent.AtomicLongMap;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.util.PipelineUtil;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.RedisDataStructureTypeEnum;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.constant.ValueSizeDistriEnum;
import com.shcj.cache.util.ConstUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisRedirectionException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * value size 分析：pipeline 批量扫描，采集分布 / 内存占比 / Top10（按字节从大到小）。
 */
@Component("RedisServerKeyValueAnalysisTask")
@Scope(SCOPE_PROTOTYPE)
public class RedisServerKeyValueAnalysisTask extends BaseTask {

    private String host;

    private int port;

    private long appId;

    private long auditId;

    /** 每批 SCAN 数量，越大越快（默认 500） */
    private final static int SCAN_COUNT = 500;

    private final static int TOP_KEY_LIMIT = 10;

    @Override
    public List<String> getTaskSteps() {
        List<String> taskStepList = new ArrayList<String>();
        taskStepList.add(TaskConstants.INIT_METHOD_KEY);
        taskStepList.add("checkIsRun");
        taskStepList.add("keyValueAnalysis");
        return taskStepList;
    }

    @Override
    public TaskFlowStatusEnum init() {
        super.init();

        appId = MapUtils.getLongValue(paramMap, TaskConstants.APPID_KEY);
        if (appId <= 0) {
            logger.error(marker, "task {} appId {} is wrong", taskId, appId);
            return TaskFlowStatusEnum.ABORT;
        }

        auditId = MapUtils.getLongValue(paramMap, TaskConstants.AUDIT_ID_KEY);
        if (auditId <= 0) {
            logger.error(marker, "task {} auditId {} is wrong", taskId, auditId);
            return TaskFlowStatusEnum.ABORT;
        }

        host = MapUtils.getString(paramMap, TaskConstants.HOST_KEY);
        if (StringUtils.isBlank(host)) {
            logger.error(marker, "task {} host is empty", taskId);
            return TaskFlowStatusEnum.ABORT;
        }

        port = MapUtils.getIntValue(paramMap, TaskConstants.PORT_KEY);
        if (port <= 0) {
            logger.error(marker, "task {} port {} is wrong", taskId, port);
            return TaskFlowStatusEnum.ABORT;
        }

        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum checkIsRun() {
        if (!redisCenter.isRun(appId, host, port)) {
            logger.error(marker, "{} {}:{} is not run", appId, host, port);
            return TaskFlowStatusEnum.ABORT;
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum keyValueAnalysis() {
        long startTime = System.currentTimeMillis();

        Map<ValueSizeDistriEnum, Long> valueSizeCountMap = new HashMap<ValueSizeDistriEnum, Long>();
        AtomicLongMap<String> typeMemoryMap = AtomicLongMap.create();
        List<TopKeyCandidate> topKeyList = new ArrayList<TopKeyCandidate>();

        Jedis jedis = null;
        try {
            jedis = redisCenter.getJedis(appId, host, port);
            try {
                jedis.readonly();
            } catch (Exception ex) {
                logger.info("jedis set readonly fail. appId={} host={} port={}", appId, host, port, ex);
            }

            long dbSize = jedis.dbSize();
            if (dbSize == 0) {
                logger.info(marker, "{} {}:{} dbsize is {}", appId, host, port, dbSize);
                return TaskFlowStatusEnum.SUCCESS;
            }
            logger.info(marker, "{} {}:{} total key is {} ", appId, host, port, dbSize);

            ScanParams scanParams = new ScanParams().count(SCAN_COUNT);
            byte[] cursor = "0".getBytes(Charset.forName("UTF-8"));

            long count = 0;
            int totalSplit = 10;
            int curSplit = 1;
            while (true) {
                try {
                    ScanResult<byte[]> scanResult = jedis.scan(cursor, scanParams);
                    cursor = scanResult.getCursorAsBytes();
                    List<byte[]> keyList = scanResult.getResult();

                    if (!keyList.isEmpty()) {
                        processKeyBatch(jedis, keyList, valueSizeCountMap, typeMemoryMap, topKeyList);
                    }

                    count += keyList.size();
                    if (count > dbSize / totalSplit * curSplit) {
                        logger.info(marker, "{} {}:{} has already anlysis {}% {} key ", appId, host, port,
                                curSplit * 10, count);
                        curSplit++;
                    }
                } catch (Exception e) {
                    logger.error(marker, e.getMessage(), e);
                } finally {
                    if (Arrays.equals("0".getBytes(Charset.forName("UTF-8")), cursor)) {
                        break;
                    }
                }
            }
            logger.info(marker, "{} {}:{} analysis key value size successfully, cost time is {} ms, total key is {}",
                    appId, host, port, (System.currentTimeMillis() - startTime), count);

            if (MapUtils.isNotEmpty(valueSizeCountMap)) {
                String keyValueSizeResultKey = ConstUtils.getRedisServerValueSizeKey(appId, auditId);
                Map<String, Long> sizeCounts = new HashMap<String, Long>();
                for (Entry<ValueSizeDistriEnum, Long> entry : valueSizeCountMap.entrySet()) {
                    sizeCounts.put(entry.getKey().getValue(), entry.getValue());
                }
                flushAssistZsetCounts(keyValueSizeResultKey, sizeCounts, "键值大小");
            } else if (dbSize > 0) {
                logger.warn(marker, "{} {}:{} value size distri is empty while dbsize={}", appId, host, port, dbSize);
            }

            saveTypeMemorySafely(typeMemoryMap);
            saveTopKeysSafely(topKeyList);
            requireLocalStatsWhenDbNonEmpty(dbSize,
                    MapUtils.isNotEmpty(valueSizeCountMap) || !typeMemoryMap.isEmpty() || !topKeyList.isEmpty(),
                    host, port, "键值/内存");

            return TaskFlowStatusEnum.SUCCESS;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            logger.error(marker, e.getMessage(), e);
            return TaskFlowStatusEnum.ABORT;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    private void processKeyBatch(Jedis jedis, List<byte[]> keyList,
                                 Map<ValueSizeDistriEnum, Long> valueSizeCountMap,
                                 AtomicLongMap<String> typeMemoryMap,
                                 List<TopKeyCandidate> topKeyList) {
        List<String> keyNameList = new ArrayList<String>(keyList.size());
        for (byte[] key : keyList) {
            keyNameList.add(new String(key, StandardCharsets.UTF_8));
        }

        Pipeline pipeline = jedis.pipelined();
        for (String keyName : keyNameList) {
            pipeline.type(keyName);
            PipelineUtil.memoryUsage(pipeline, keyName);
        }
        List<Object> pipelineResult;
        try {
            pipelineResult = pipeline.syncAndReturnAll();
        } catch (JedisRedirectionException e) {
            return;
        } catch (Exception e) {
            logger.warn(marker, "pipeline type/memory failed {}:{} {}", host, port, e.getMessage());
            return;
        }

        List<Integer> needEstimateIdx = new ArrayList<Integer>();
        List<String> needEstimateKeys = new ArrayList<String>();
        List<String> needEstimateTypes = new ArrayList<String>();

        for (int i = 0; i < keyNameList.size(); i++) {
            String keyName = keyNameList.get(i);
            Object typeObj = pipelineResult.get(i * 2);
            Object memObj = pipelineResult.get(i * 2 + 1);

            if (!(typeObj instanceof String)) {
                continue;
            }
            String type = String.valueOf(typeObj);
            if ("none".equalsIgnoreCase(type)) {
                continue;
            }

            long valueBytes = parseMemoryUsage(memObj);
            if (valueBytes <= 0) {
                needEstimateIdx.add(i);
                needEstimateKeys.add(keyName);
                needEstimateTypes.add(type);
                continue;
            }
            applyKeyStats(keyName, type, valueBytes, valueSizeCountMap, typeMemoryMap, topKeyList);
        }

        if (!needEstimateKeys.isEmpty()) {
            Map<String, Long> estimateMap = batchEstimateBytes(jedis, needEstimateKeys, needEstimateTypes);
            for (int j = 0; j < needEstimateKeys.size(); j++) {
                String keyName = needEstimateKeys.get(j);
                String type = needEstimateTypes.get(j);
                Long valueBytes = estimateMap.get(keyName);
                if (valueBytes == null || valueBytes <= 0) {
                    continue;
                }
                applyKeyStats(keyName, type, valueBytes, valueSizeCountMap, typeMemoryMap, topKeyList);
            }
        }
    }

    private void applyKeyStats(String keyName, String type, long valueBytes,
                               Map<ValueSizeDistriEnum, Long> valueSizeCountMap,
                               AtomicLongMap<String> typeMemoryMap,
                               List<TopKeyCandidate> topKeyList) {
        try {
            typeMemoryMap.addAndGet(type, valueBytes);
            trackTopKey(topKeyList, keyName, type, valueBytes, host, port);
        } catch (Exception e) {
            logger.warn(marker, "apply key stats failed key={}", keyName, e);
        }

        ValueSizeDistriEnum valueSizeDistriEnum = ValueSizeDistriEnum.getRightSizeBetween(valueBytes);
        if (valueSizeDistriEnum == null) {
            return;
        }
        Long bucketCount = valueSizeCountMap.get(valueSizeDistriEnum);
        valueSizeCountMap.put(valueSizeDistriEnum, bucketCount == null ? 1L : bucketCount + 1);
    }

    private Map<String, Long> batchEstimateBytes(Jedis jedis, List<String> keyNames, List<String> types) {
        Map<String, Long> result = new HashMap<String, Long>();
        Pipeline pipeline = jedis.pipelined();
        for (int i = 0; i < keyNames.size(); i++) {
            addEstimateCommand(pipeline, keyNames.get(i), types.get(i));
        }
        List<Object> estResults;
        try {
            estResults = pipeline.syncAndReturnAll();
        } catch (Exception e) {
            return result;
        }
        for (int i = 0; i < keyNames.size(); i++) {
            Object obj = estResults.get(i);
            if (obj instanceof Number) {
                result.put(keyNames.get(i), ((Number) obj).longValue());
            }
        }
        return result;
    }

    private void addEstimateCommand(Pipeline pipeline, String keyName, String type) {
        if (RedisDataStructureTypeEnum.string.getValue().equals(type)) {
            pipeline.strlen(keyName);
        } else if (RedisDataStructureTypeEnum.hash.getValue().equals(type)) {
            pipeline.hlen(keyName);
        } else if (RedisDataStructureTypeEnum.list.getValue().equals(type)) {
            pipeline.llen(keyName);
        } else if (RedisDataStructureTypeEnum.set.getValue().equals(type)) {
            pipeline.scard(keyName);
        } else if (RedisDataStructureTypeEnum.zset.getValue().equals(type)) {
            pipeline.zcard(keyName);
        } else {
            pipeline.type(keyName);
        }
    }

    private long parseMemoryUsage(Object memObj) {
        if (memObj instanceof JedisDataException) {
            return 0L;
        }
        if (memObj instanceof Exception) {
            return 0L;
        }
        if (memObj instanceof Long) {
            return (Long) memObj;
        }
        if (memObj instanceof Integer) {
            return ((Integer) memObj).longValue();
        }
        return 0L;
    }

    private void saveTypeMemorySafely(AtomicLongMap<String> typeMemoryMap) {
        if (typeMemoryMap.isEmpty()) {
            return;
        }
        String typeMemoryResultKey = ConstUtils.getRedisServerTypeMemoryKey(appId, auditId);
        Map<String, Long> memoryCounts = new HashMap<String, Long>(typeMemoryMap.asMap());
        flushAssistZsetCounts(typeMemoryResultKey, memoryCounts, "类型内存");
    }

    private void saveTopKeysSafely(List<TopKeyCandidate> topKeyList) {
        if (topKeyList.isEmpty()) {
            return;
        }
        String topKeyRedisKey = ConstUtils.getRedisServerTopKeyKey(appId, auditId);
        int saved = 0;
        for (TopKeyCandidate entry : topKeyList) {
            String member = ConstUtils.encodeTopKeyMember(entry.ip, entry.port, entry.type, entry.keyName);
            if (assistRedisService.zadd(topKeyRedisKey, entry.bytes, member)) {
                saved++;
            }
        }
        logger.info(marker, "{} {}:{} saved top {}/{} keys to {}", appId, host, port, saved, topKeyList.size(), topKeyRedisKey);
        if (saved == 0) {
            throw new BizException("{} {}:{} top keys not persisted to assist redis key {}", appId, host, port, topKeyRedisKey);
        }
        if (!assistRedisService.hasZsetData(topKeyRedisKey)) {
            throw new BizException("{} {}:{} top keys zset missing after save, key={}", appId, host, port, topKeyRedisKey);
        }
    }

    private void trackTopKey(List<TopKeyCandidate> topKeyList, String keyName, String type, long bytes, String host, int port) {
        TopKeyCandidate entry = new TopKeyCandidate();
        entry.keyName = keyName;
        entry.type = type;
        entry.bytes = bytes;
        entry.ip = host;
        entry.port = port;
        topKeyList.add(entry);
        topKeyList.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        if (topKeyList.size() > TOP_KEY_LIMIT) {
            topKeyList.subList(TOP_KEY_LIMIT, topKeyList.size()).clear();
        }
    }

    private static class TopKeyCandidate {
        private String ip;
        private int port;
        private String type;
        private String keyName;
        private long bytes;
    }

}
