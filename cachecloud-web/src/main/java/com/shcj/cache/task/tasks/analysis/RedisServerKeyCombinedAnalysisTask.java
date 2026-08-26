package com.shcj.cache.task.tasks.analysis;

import com.google.common.util.concurrent.AtomicLongMap;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.dao.InstanceBigKeyDao;
import com.shcj.cache.redis.util.PipelineUtil;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.IdleTimeDistriEnum;
import com.shcj.cache.task.constant.RedisDataStructureTypeEnum;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.constant.TtlTimeDistriEnum;
import com.shcj.cache.task.constant.ValueSizeDistriEnum;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisBigKeyDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisStatsSnapshotDto;
import com.shcj.cache.web.controller.api.dto.KeyPrefixStatDto;
import com.shcj.cache.web.controller.api.dto.ParamCountDto;
import com.shcj.cache.web.service.KeyAnalysisStatsStoreService;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Date;
import com.shcj.cache.task.entity.InstanceBigKey;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * 一次 SCAN 完成类型 / TTL / 空闲 / 大小 / 内存占比 / Top100 统计（替代多轮全量扫描）。
 */
@Component("RedisServerKeyCombinedAnalysisTask")
@Scope(SCOPE_PROTOTYPE)
public class RedisServerKeyCombinedAnalysisTask extends BaseTask {

    @Autowired
    private KeyAnalysisStatsStoreService keyAnalysisStatsStoreService;

    @Autowired
    private InstanceBigKeyDao instanceBigKeyDao;

    private static final int SCAN_COUNT = 500;
    private static final int TOP_KEY_LIMIT = 100;
    private static final int CMDS_PER_KEY = 4;

    private String host;
    private int port;
    private long appId;
    private long auditId;
    private long bigKeyStringBytes;
    private long bigKeyCollectionElements;

    /**
     * 键值大小极值。区间分布只说明「落在哪一档」，看不出真实的最大/最小值，
     * 因此扫描时顺带累计原始量，平均值由 sum/count 得出而不是拿区间中值估算。
     * 任务是 prototype scope，这几个字段与 host/port 一样按次执行；
     * combinedKeyAnalysis 入口仍显式重置，避免任务重试复用实例时把上一轮的极值带进来。
     */
    private long valueSizeMaxBytes;
    private long valueSizeMinBytes;
    private long valueSizeSumBytes;
    private long valueSizeSampleCount;

    @Override
    public List<String> getTaskSteps() {
        List<String> taskStepList = new ArrayList<String>();
        taskStepList.add(TaskConstants.INIT_METHOD_KEY);
        taskStepList.add("checkIsRun");
        taskStepList.add("combinedKeyAnalysis");
        return taskStepList;
    }

    @Override
    public TaskFlowStatusEnum init() {
        super.init();
        appId = MapUtils.getLongValue(paramMap, TaskConstants.APPID_KEY);
        if (appId <= 0) {
            throw new BizException("task {} appId {} is wrong", taskId, appId);
        }
        auditId = MapUtils.getLongValue(paramMap, TaskConstants.AUDIT_ID_KEY);
        if (auditId <= 0) {
            throw new BizException("task {} auditId {} is wrong", taskId, auditId);
        }
        host = MapUtils.getString(paramMap, TaskConstants.HOST_KEY);
        if (StringUtils.isBlank(host)) {
            throw new BizException("task {} host is empty", taskId);
        }
        port = MapUtils.getIntValue(paramMap, TaskConstants.PORT_KEY);
        if (port <= 0) {
            throw new BizException("task {} port {} is wrong", taskId, port);
        }
        bigKeyStringBytes = MapUtils.getLongValue(paramMap, TaskConstants.BIG_KEY_STRING_BYTES_KEY,
                ConstUtils.DEFAULT_STRING_MAX_LENGTH);
        bigKeyCollectionElements = MapUtils.getLongValue(paramMap, TaskConstants.BIG_KEY_COLLECTION_ELEMENTS_KEY,
                ConstUtils.DEFAULT_HASH_MAX_LENGTH);
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum checkIsRun() {
        if (!redisCenter.isRun(appId, host, port)) {
            throw new BizException("{} {}:{} is not run", appId, host, port);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum combinedKeyAnalysis() {
        long startTime = System.currentTimeMillis();
        valueSizeMaxBytes = 0;
        valueSizeMinBytes = 0;
        valueSizeSumBytes = 0;
        valueSizeSampleCount = 0;

        AtomicLongMap<String> typeCountMap = AtomicLongMap.create();
        AtomicLongMap<TtlTimeDistriEnum> ttlTimeCountMap = AtomicLongMap.create();
        AtomicLongMap<IdleTimeDistriEnum> idleTimeCountMap = AtomicLongMap.create();
        Map<ValueSizeDistriEnum, Long> valueSizeCountMap = new HashMap<ValueSizeDistriEnum, Long>();
        AtomicLongMap<String> typeMemoryMap = AtomicLongMap.create();
        List<TopKeyCandidate> topKeyList = new ArrayList<TopKeyCandidate>();
        Map<String, PrefixAccumulator> prefixStats = new HashMap<String, PrefixAccumulator>();

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
            logger.info(marker, "{} {}:{} combined analysis start, total key {}", appId, host, port, dbSize);

            ScanParams scanParams = new ScanParams().count(SCAN_COUNT);
            byte[] cursor = "0".getBytes(Charset.forName("UTF-8"));
            byte[] zeroCursor = "0".getBytes(StandardCharsets.UTF_8);
            long count = 0;
            int totalSplit = 10;
            int curSplit = 1;

            while (true) {
                ScanResult<byte[]> scanResult;
                try {
                    scanResult = jedis.scan(cursor, scanParams);
                } catch (Exception e) {
                    logger.error(marker, "{} {}:{} scan error: {}", appId, host, port, e.getMessage(), e);
                    throw new BizException("{} {}:{} SCAN 失败: {}", appId, host, port, e.getMessage());
                }
                cursor = scanResult.getCursorAsBytes();
                List<byte[]> keyList = scanResult.getResult();
                if (!keyList.isEmpty()) {
                    processBatch(jedis, keyList, typeCountMap, ttlTimeCountMap, idleTimeCountMap,
                            valueSizeCountMap, typeMemoryMap, topKeyList, prefixStats);
                }
                count += keyList.size();
                if (count > dbSize / totalSplit * curSplit) {
                    logger.info(marker, "{} {}:{} combined analysis {}% (~{} keys)", appId, host, port,
                            curSplit * 10, count);
                    curSplit++;
                }
                if (Arrays.equals(zeroCursor, cursor)) {
                    break;
                }
            }

            logger.info(marker, "{} {}:{} combined analysis done, cost {} ms, scanned {}", appId, host, port,
                    (System.currentTimeMillis() - startTime), count);

            persistAllStats(typeCountMap, ttlTimeCountMap, idleTimeCountMap, valueSizeCountMap, typeMemoryMap,
                    topKeyList, prefixStats, dbSize);

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

    private void processBatch(Jedis jedis, List<byte[]> keyList,
                              AtomicLongMap<String> typeCountMap,
                              AtomicLongMap<TtlTimeDistriEnum> ttlTimeCountMap,
                              AtomicLongMap<IdleTimeDistriEnum> idleTimeCountMap,
                              Map<ValueSizeDistriEnum, Long> valueSizeCountMap,
                              AtomicLongMap<String> typeMemoryMap,
                              List<TopKeyCandidate> topKeyList,
                              Map<String, PrefixAccumulator> prefixStats) {
        List<String> keyNameList = new ArrayList<String>(keyList.size());
        for (byte[] key : keyList) {
            keyNameList.add(new String(key, StandardCharsets.UTF_8));
        }
        List<String> keyTypes = new ArrayList<String>(java.util.Collections.nCopies(keyList.size(), null));
        List<Long> keyMemoryBytes = new ArrayList<Long>(java.util.Collections.nCopies(keyList.size(), 0L));

        Pipeline pipeline = jedis.pipelined();
        for (byte[] key : keyList) {
            pipeline.type(key);
            pipeline.ttl(key);
            pipeline.objectIdletime(key);
            PipelineUtil.memoryUsage(pipeline, new String(key, StandardCharsets.UTF_8));
        }
        List<Object> pipelineResult;
        try {
            pipelineResult = pipeline.syncAndReturnAll();
        } catch (JedisRedirectionException e) {
            logger.warn(marker, "combined pipeline redirection {}:{}, fallback sequential", host, port);
            processBatchSequentially(jedis, keyNameList, typeCountMap, ttlTimeCountMap, idleTimeCountMap,
                    valueSizeCountMap, typeMemoryMap, topKeyList, prefixStats);
            return;
        } catch (Exception e) {
            logger.warn(marker, "combined pipeline failed {}:{} {}, fallback sequential", host, port, e.getMessage());
            processBatchSequentially(jedis, keyNameList, typeCountMap, ttlTimeCountMap, idleTimeCountMap,
                    valueSizeCountMap, typeMemoryMap, topKeyList, prefixStats);
            return;
        }

        List<Integer> needEstimateIdx = new ArrayList<Integer>();
        List<String> needEstimateKeys = new ArrayList<String>();
        List<String> needEstimateTypes = new ArrayList<String>();

        for (int i = 0; i < keyNameList.size(); i++) {
            int base = i * CMDS_PER_KEY;
            Object typeObj = pipelineResult.get(base);
            Object ttlObj = pipelineResult.get(base + 1);
            Object idleObj = pipelineResult.get(base + 2);
            Object memObj = pipelineResult.get(base + 3);

            if (!(typeObj instanceof String)) {
                continue;
            }
            String type = String.valueOf(typeObj);
            if ("none".equalsIgnoreCase(type)) {
                continue;
            }
            keyTypes.set(i, type);

            typeCountMap.incrementAndGet(type);
            applyTtl(ttlObj, ttlTimeCountMap);
            applyIdle(idleObj, idleTimeCountMap);

            long valueBytes = parseMemoryUsage(memObj);
            keyMemoryBytes.set(i, valueBytes);
            String keyName = keyNameList.get(i);
            if (valueBytes <= 0) {
                needEstimateIdx.add(i);
                needEstimateKeys.add(keyName);
                needEstimateTypes.add(type);
                continue;
            }
            applyValueStats(keyName, type, valueBytes, valueSizeCountMap, typeMemoryMap, topKeyList, prefixStats);
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
                keyMemoryBytes.set(needEstimateIdx.get(j), valueBytes);
                applyValueStats(keyName, type, valueBytes, valueSizeCountMap, typeMemoryMap, topKeyList, prefixStats);
            }
        }
        persistBigKeys(jedis, keyNameList, keyTypes, keyMemoryBytes);
    }

    private void applyTtl(Object ttlObj, AtomicLongMap<TtlTimeDistriEnum> ttlTimeCountMap) {
        if (!(ttlObj instanceof Long)) {
            return;
        }
        long ttlSeconds = (Long) ttlObj;
        TtlTimeDistriEnum ttlTimeDistriEnum;
        if (ttlSeconds == -1) {
            ttlTimeDistriEnum = TtlTimeDistriEnum.BETWEEN_PERSIST_HOURS;
        } else {
            ttlTimeDistriEnum = TtlTimeDistriEnum.getRightTtlDistri(ttlSeconds / 3600);
        }
        if (ttlTimeDistriEnum != null) {
            ttlTimeCountMap.incrementAndGet(ttlTimeDistriEnum);
        }
    }

    private void applyIdle(Object idleObj, AtomicLongMap<IdleTimeDistriEnum> idleTimeCountMap) {
        if (!(idleObj instanceof Long)) {
            return;
        }
        long idleHours = ((Long) idleObj) / 3600;
        IdleTimeDistriEnum idleTimeDistriEnum = IdleTimeDistriEnum.getRightIdleDistri(idleHours);
        if (idleTimeDistriEnum != null) {
            idleTimeCountMap.incrementAndGet(idleTimeDistriEnum);
        }
    }

    private void applyValueStats(String keyName, String type, long valueBytes,
                                 Map<ValueSizeDistriEnum, Long> valueSizeCountMap,
                                 AtomicLongMap<String> typeMemoryMap,
                                 List<TopKeyCandidate> topKeyList,
                                 Map<String, PrefixAccumulator> prefixStats) {
        typeMemoryMap.addAndGet(type, valueBytes);
        trackValueSizeExtremes(valueBytes);
        trackTopKey(topKeyList, keyName, type, valueBytes);
        String prefixKey = type + "\u0000" + extractPrefix(keyName);
        PrefixAccumulator prefix = prefixStats.computeIfAbsent(prefixKey,
                key -> new PrefixAccumulator(type, extractPrefix(keyName)));
        prefix.count++;
        prefix.bytes += valueBytes;
        ValueSizeDistriEnum bucket = ValueSizeDistriEnum.getRightSizeBetween(valueBytes);
        if (bucket != null) {
            Long bucketCount = valueSizeCountMap.get(bucket);
            valueSizeCountMap.put(bucket, bucketCount == null ? 1L : bucketCount + 1);
        }
    }

    /** 调用方已保证 valueBytes &gt; 0；min 用 0 作「尚无样本」的哨兵，故首个样本必须无条件写入 */
    private void trackValueSizeExtremes(long valueBytes) {
        valueSizeSampleCount++;
        valueSizeSumBytes += valueBytes;
        if (valueBytes > valueSizeMaxBytes) {
            valueSizeMaxBytes = valueBytes;
        }
        if (valueSizeMinBytes <= 0 || valueBytes < valueSizeMinBytes) {
            valueSizeMinBytes = valueBytes;
        }
    }

    private void persistAllStats(AtomicLongMap<String> typeCountMap,
                                 AtomicLongMap<TtlTimeDistriEnum> ttlTimeCountMap,
                                 AtomicLongMap<IdleTimeDistriEnum> idleTimeCountMap,
                                 Map<ValueSizeDistriEnum, Long> valueSizeCountMap,
                                 AtomicLongMap<String> typeMemoryMap,
                                 List<TopKeyCandidate> topKeyList,
                                 Map<String, PrefixAccumulator> prefixStats,
                                 long dbSize) {
        boolean hasStats = !typeCountMap.isEmpty() || !ttlTimeCountMap.isEmpty() || !idleTimeCountMap.isEmpty()
                || MapUtils.isNotEmpty(valueSizeCountMap) || !typeMemoryMap.isEmpty() || !topKeyList.isEmpty();
        requireLocalStatsWhenDbNonEmpty(dbSize, hasStats, host, port, "键值综合分析", appId, auditId);

        try {
            keyAnalysisStatsStoreService.mergeNodeSnapshot(appId, auditId,
                    buildNodeSnapshot(typeCountMap, ttlTimeCountMap, idleTimeCountMap, valueSizeCountMap,
                            typeMemoryMap, topKeyList, prefixStats));
        } catch (Exception e) {
            logger.warn(marker, "appId {} auditId {} {}:{} merge stats to MySQL failed: {}", appId, auditId, host, port,
                    e.getMessage());
        }

        if (!typeCountMap.isEmpty()) {
            Map<String, Long> typeCounts = new HashMap<String, Long>(typeCountMap.asMap());
            flushAssistZsetCounts(ConstUtils.getRedisServerTypeKey(appId, auditId), typeCounts, "键类型", appId, auditId);
        }
        if (!ttlTimeCountMap.isEmpty()) {
            Map<String, Long> ttlCounts = new HashMap<String, Long>();
            for (Entry<TtlTimeDistriEnum, Long> entry : ttlTimeCountMap.asMap().entrySet()) {
                ttlCounts.put(entry.getKey().getValue(), entry.getValue());
            }
            flushAssistZsetCounts(ConstUtils.getRedisServerTtlKey(appId, auditId), ttlCounts, "TTL", appId, auditId);
        }
        if (!idleTimeCountMap.isEmpty()) {
            Map<String, Long> idleCounts = new HashMap<String, Long>();
            for (Entry<IdleTimeDistriEnum, Long> entry : idleTimeCountMap.asMap().entrySet()) {
                idleCounts.put(entry.getKey().getValue(), entry.getValue());
            }
            flushAssistZsetCounts(ConstUtils.getRedisServerIdleKey(appId, auditId), idleCounts, "空闲键", appId, auditId);
        }
        if (MapUtils.isNotEmpty(valueSizeCountMap)) {
            Map<String, Long> sizeCounts = new HashMap<String, Long>();
            for (Entry<ValueSizeDistriEnum, Long> entry : valueSizeCountMap.entrySet()) {
                sizeCounts.put(entry.getKey().getValue(), entry.getValue());
            }
            flushAssistZsetCounts(ConstUtils.getRedisServerValueSizeKey(appId, auditId), sizeCounts, "键值大小", appId,
                    auditId);
        }
        if (!typeMemoryMap.isEmpty()) {
            flushAssistZsetCounts(ConstUtils.getRedisServerTypeMemoryKey(appId, auditId),
                    new HashMap<String, Long>(typeMemoryMap.asMap()), "类型内存", appId, auditId);
        }
        if (!topKeyList.isEmpty()) {
            String topKeyRedisKey = ConstUtils.getRedisServerTopKeyKey(appId, auditId);
            int saved = 0;
            for (TopKeyCandidate entry : topKeyList) {
                String member = ConstUtils.encodeTopKeyMember(entry.ip, entry.port, entry.type, entry.keyName);
                if (assistRedisService.zadd(topKeyRedisKey, entry.bytes, member)) {
                    saved++;
                }
            }
            logger.info(marker, "{} {}:{} saved top {}/{} keys", appId, host, port, saved, topKeyList.size());
            if (saved == 0 || !assistRedisService.hasZsetData(topKeyRedisKey)) {
                assistRedisService.appendKeyAnalysisRisk(appId, auditId,
                        host + ":" + port + " Top10 大 key 未能写入辅助 Redis（key=" + topKeyRedisKey + "）");
                logger.warn(marker, "{} {}:{} top keys not persisted, key={}", appId, host, port, topKeyRedisKey);
            }
        }
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
        if (memObj instanceof JedisDataException || memObj instanceof Exception) {
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

    private void trackTopKey(List<TopKeyCandidate> topKeyList, String keyName, String type, long bytes) {
        TopKeyCandidate entry = new TopKeyCandidate();
        entry.keyName = truncateKey(keyName);
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

    private void processBatchSequentially(Jedis jedis, List<String> keyNameList,
                                          AtomicLongMap<String> typeCountMap,
                                          AtomicLongMap<TtlTimeDistriEnum> ttlTimeCountMap,
                                          AtomicLongMap<IdleTimeDistriEnum> idleTimeCountMap,
                                          Map<ValueSizeDistriEnum, Long> valueSizeCountMap,
                                          AtomicLongMap<String> typeMemoryMap,
                                          List<TopKeyCandidate> topKeyList,
                                          Map<String, PrefixAccumulator> prefixStats) {
        List<InstanceBigKey> bigKeyRows = new ArrayList<InstanceBigKey>();
        for (String keyName : keyNameList) {
            try {
                String type = jedis.type(keyName);
                if (StringUtils.isBlank(type) || "none".equalsIgnoreCase(type)) {
                    continue;
                }
                typeCountMap.incrementAndGet(type);
                applyTtl(jedis.ttl(keyName), ttlTimeCountMap);
                applyIdle(jedis.objectIdletime(keyName), idleTimeCountMap);
                long valueBytes = parseMemoryUsage(jedis.memoryUsage(keyName));
                if (valueBytes <= 0) {
                    Map<String, Long> estimateMap = batchEstimateBytes(jedis,
                            java.util.Collections.singletonList(keyName),
                            java.util.Collections.singletonList(type));
                    Long estimated = estimateMap.get(keyName);
                    valueBytes = estimated != null ? estimated : 0L;
                }
                if (valueBytes > 0) {
                    applyValueStats(keyName, type, valueBytes, valueSizeCountMap, typeMemoryMap, topKeyList, prefixStats);
                }
                long bigKeyMetric = RedisDataStructureTypeEnum.string.getValue().equals(type)
                        ? valueBytes : readCollectionLength(jedis, keyName, type);
                addBigKeyRow(bigKeyRows, keyName, type, bigKeyMetric);
            } catch (Exception e) {
                logger.debug(marker, "sequential key stats failed {}:{} key={} {}", host, port, keyName,
                        e.getMessage());
            }
        }
        if (!bigKeyRows.isEmpty()) {
            instanceBigKeyDao.batchSave(bigKeyRows);
        }
    }

    private KeyAnalysisStatsSnapshotDto buildNodeSnapshot(AtomicLongMap<String> typeCountMap,
            AtomicLongMap<TtlTimeDistriEnum> ttlTimeCountMap,
            AtomicLongMap<IdleTimeDistriEnum> idleTimeCountMap,
            Map<ValueSizeDistriEnum, Long> valueSizeCountMap,
            AtomicLongMap<String> typeMemoryMap,
            List<TopKeyCandidate> topKeyList,
            Map<String, PrefixAccumulator> prefixStats) {
        KeyAnalysisStatsSnapshotDto snapshot = new KeyAnalysisStatsSnapshotDto();
        for (Entry<String, Long> entry : typeCountMap.asMap().entrySet()) {
            snapshot.getKeyTypeDistri().add(new ParamCountDto(entry.getKey(), entry.getValue(), ""));
        }
        for (Entry<TtlTimeDistriEnum, Long> entry : ttlTimeCountMap.asMap().entrySet()) {
            if (entry.getKey() != null) {
                snapshot.getKeyTtlDistri().add(new ParamCountDto(entry.getKey().getInfo(), entry.getValue(), ""));
            }
        }
        for (Entry<IdleTimeDistriEnum, Long> entry : idleTimeCountMap.asMap().entrySet()) {
            if (entry.getKey() != null) {
                snapshot.getIdleKeyDistri().add(new ParamCountDto(entry.getKey().getInfo(), entry.getValue(), ""));
            }
        }
        for (Entry<ValueSizeDistriEnum, Long> entry : valueSizeCountMap.entrySet()) {
            if (entry.getKey() != null) {
                snapshot.getKeyValueSizeDistri()
                        .add(new ParamCountDto(entry.getKey().getInfo(), entry.getValue(), ""));
            }
        }
        for (Entry<String, Long> entry : typeMemoryMap.asMap().entrySet()) {
            snapshot.getKeyTypeMemoryDistri().add(new ParamCountDto(entry.getKey(), entry.getValue(), ""));
        }
        boolean hasMemory = false;
        for (TopKeyCandidate candidate : topKeyList) {
            KeyAnalysisBigKeyDto row = new KeyAnalysisBigKeyDto();
            row.setInstance(host + ":" + port);
            row.setKeyName(candidate.keyName);
            row.setType(candidate.type);
            row.setLength(candidate.bytes);
            if (candidate.bytes > 0) {
                hasMemory = true;
                row.setSizeLabel(KeyAnalysisStatsStoreService.formatMemoryBytes(candidate.bytes));
            } else {
                row.setSizeLabel(String.valueOf(candidate.bytes));
            }
            snapshot.getTopKeys().add(row);
        }
        snapshot.setTopKeyFromMemoryScan(hasMemory);
        snapshot.setValueSizeMaxBytes(valueSizeMaxBytes);
        snapshot.setValueSizeMinBytes(valueSizeMinBytes);
        snapshot.setValueSizeSumBytes(valueSizeSumBytes);
        snapshot.setValueSizeSampleCount(valueSizeSampleCount);
        snapshot.setHasTypeMemoryData(!typeMemoryMap.isEmpty());
        snapshot.setBigKeyStringBytes(bigKeyStringBytes);
        snapshot.setBigKeyCollectionElements(bigKeyCollectionElements);
        Map<String, List<PrefixAccumulator>> byType = new HashMap<String, List<PrefixAccumulator>>();
        for (PrefixAccumulator value : prefixStats.values()) {
            byType.computeIfAbsent(value.type, key -> new ArrayList<PrefixAccumulator>()).add(value);
        }
        for (List<PrefixAccumulator> values : byType.values()) {
            values.sort((a, b) -> Long.compare(b.count, a.count));
            for (int i = 0; i < Math.min(50, values.size()); i++) {
                PrefixAccumulator value = values.get(i);
                KeyPrefixStatDto row = new KeyPrefixStatDto();
                row.setType(value.type);
                row.setPrefix(value.prefix);
                row.setCount(value.count);
                row.setBytes(value.bytes);
                row.setBytesLabel(KeyAnalysisStatsStoreService.formatMemoryBytes(value.bytes));
                snapshot.getKeyPrefixTop().add(row);
            }
        }
        boolean hasDistributionData = !snapshot.getKeyTypeDistri().isEmpty()
                || !snapshot.getKeyTtlDistri().isEmpty()
                || !snapshot.getIdleKeyDistri().isEmpty()
                || !snapshot.getKeyValueSizeDistri().isEmpty()
                || !snapshot.getKeyTypeMemoryDistri().isEmpty()
                || !snapshot.getTopKeys().isEmpty();
        snapshot.setHasDistributionData(hasDistributionData);
        return snapshot;
    }

    private void persistBigKeys(Jedis jedis, List<String> keyNames, List<String> keyTypes,
                                List<Long> keyMemoryBytes) {
        if (keyNames.isEmpty()) return;
        List<InstanceBigKey> rows = new ArrayList<InstanceBigKey>();
        List<Integer> queryIndexes = new ArrayList<Integer>();
        Pipeline sizePipe = jedis.pipelined();
        for (int i = 0; i < keyNames.size(); i++) {
            String type = keyTypes.get(i);
            if (StringUtils.isBlank(type)) continue;
            long memoryBytes = keyMemoryBytes.get(i) == null ? 0L : keyMemoryBytes.get(i);
            if (RedisDataStructureTypeEnum.string.getValue().equals(type) && memoryBytes > 0) {
                addBigKeyRow(rows, keyNames.get(i), type, memoryBytes);
            } else if (isBigKeySupportedType(type)) {
                queryIndexes.add(i);
                addEstimateCommand(sizePipe, keyNames.get(i), type);
            }
        }
        if (!queryIndexes.isEmpty()) {
            try {
                List<Object> sizes = sizePipe.syncAndReturnAll();
                for (int i = 0; i < queryIndexes.size(); i++) {
                    int keyIndex = queryIndexes.get(i);
                    Object value = sizes.get(i);
                    long size = value instanceof Number ? ((Number) value).longValue() : 0L;
                    addBigKeyRow(rows, keyNames.get(keyIndex), keyTypes.get(keyIndex), size);
                }
            } catch (Exception e) {
                logger.warn(marker, "{} {}:{} BigKey metric pipeline failed: {}", appId, host, port, e.getMessage());
            }
        }
        if (!rows.isEmpty()) instanceBigKeyDao.batchSave(rows);
    }

    private boolean isBigKeySupportedType(String type) {
        return RedisDataStructureTypeEnum.string.getValue().equals(type)
                || RedisDataStructureTypeEnum.hash.getValue().equals(type)
                || RedisDataStructureTypeEnum.list.getValue().equals(type)
                || RedisDataStructureTypeEnum.set.getValue().equals(type)
                || RedisDataStructureTypeEnum.zset.getValue().equals(type);
    }

    private void addBigKeyRow(List<InstanceBigKey> rows, String keyName, String type, long metric) {
        boolean big = RedisDataStructureTypeEnum.string.getValue().equals(type) ? metric >= bigKeyStringBytes
                : isBigKeySupportedType(type) && metric >= bigKeyCollectionElements;
        if (!big) return;
        InstanceBigKey row = new InstanceBigKey();
        row.setAppId(appId);
        row.setAuditId(auditId);
        row.setIp(host);
        row.setPort(port);
        row.setBigKey(truncateKey(keyName));
        row.setType(type);
        row.setLength(metric);
        row.setCreateTime(new Date());
        rows.add(row);
    }

    private long readCollectionLength(Jedis jedis, String keyName, String type) {
        if (RedisDataStructureTypeEnum.hash.getValue().equals(type)) return jedis.hlen(keyName);
        if (RedisDataStructureTypeEnum.list.getValue().equals(type)) return jedis.llen(keyName);
        if (RedisDataStructureTypeEnum.set.getValue().equals(type)) return jedis.scard(keyName);
        if (RedisDataStructureTypeEnum.zset.getValue().equals(type)) return jedis.zcard(keyName);
        return 0L;
    }

    private String extractPrefix(String key) {
        if (StringUtils.isBlank(key)) return "(empty)";
        int end = key.length();
        for (char separator : new char[]{':', '_', '-', '.', '/'}) {
            int index = key.indexOf(separator);
            if (index > 0 && index < end) end = index;
        }
        if (end == key.length()) {
            for (int i = 1; i < key.length(); i++) {
                if (Character.isDigit(key.charAt(i))) {
                    end = i;
                    break;
                }
            }
        }
        return truncateKey(key.substring(0, Math.min(end, 32)));
    }

    private String truncateKey(String key) {
        return key.length() <= 256 ? key : key.substring(0, 253) + "...";
    }

    private static class PrefixAccumulator {
        private final String type;
        private final String prefix;
        private long count;
        private long bytes;
        private PrefixAccumulator(String type, String prefix) { this.type = type; this.prefix = prefix; }
    }

    private static class TopKeyCandidate {
        private String ip;
        private int port;
        private String type;
        private String keyName;
        private long bytes;
    }
}
