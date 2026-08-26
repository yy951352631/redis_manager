package com.shcj.cache.web.service;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.dao.AppKeyAnalysisStatsDao;
import com.shcj.cache.dao.InstanceBigKeyDao;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.task.constant.IdleTimeDistriEnum;
import com.shcj.cache.task.constant.TtlTimeDistriEnum;
import com.shcj.cache.task.constant.ValueSizeDistriEnum;
import com.shcj.cache.task.entity.InstanceBigKey;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisBigKeyDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisResultDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisStatsSnapshotDto;
import com.shcj.cache.web.controller.api.dto.ParamCountDto;
import com.shcj.cache.web.controller.api.dto.KeyPrefixStatDto;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Tuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KeyAnalysisStatsStoreService {

    @Autowired
    private AppKeyAnalysisStatsDao appKeyAnalysisStatsDao;

    @Autowired
    private AssistRedisService assistRedisService;

    @Autowired
    private InstanceBigKeyDao instanceBigKeyDao;

    /**
     * 子任务扫描完成后直接合并写入 MySQL（不依赖 assist Redis 是否可读）。
     */
    public synchronized void mergeNodeSnapshot(long appId, long auditId, KeyAnalysisStatsSnapshotDto partial) {
        if (partial == null) {
            return;
        }
        KeyAnalysisStatsSnapshotDto merged = loadSnapshot(auditId);
        if (merged == null) {
            merged = new KeyAnalysisStatsSnapshotDto();
        }
        merged.setIdleKeyDistri(mergeParamCounts(merged.getIdleKeyDistri(), partial.getIdleKeyDistri()));
        merged.setKeyTypeDistri(mergeParamCounts(merged.getKeyTypeDistri(), partial.getKeyTypeDistri()));
        merged.setKeyTtlDistri(mergeParamCounts(merged.getKeyTtlDistri(), partial.getKeyTtlDistri()));
        merged.setKeyValueSizeDistri(mergeParamCounts(merged.getKeyValueSizeDistri(), partial.getKeyValueSizeDistri()));
        mergeValueSizeExtremes(merged, partial);
        merged.setKeyTypeMemoryDistri(mergeParamCounts(merged.getKeyTypeMemoryDistri(), partial.getKeyTypeMemoryDistri()));
        merged.setTopKeys(mergeTopKeys(merged.getTopKeys(), partial.getTopKeys()));
        merged.setKeyPrefixTop(mergePrefixStats(merged.getKeyPrefixTop(), partial.getKeyPrefixTop()));
        merged.setBigKeyStringBytes(partial.getBigKeyStringBytes());
        merged.setBigKeyCollectionElements(partial.getBigKeyCollectionElements());
        merged.setTopKeyFromMemoryScan(merged.isTopKeyFromMemoryScan() || partial.isTopKeyFromMemoryScan());
        merged.setHasTypeMemoryData(merged.isHasTypeMemoryData() || partial.isHasTypeMemoryData());
        refreshDistributionFlags(merged);
        appKeyAnalysisStatsDao.upsertStats(appId, auditId, JSON.toJSONString(merged));
    }

    /**
     * 极值不能像区间计数那样相加：max 取大、min 取小，只有 sum/count 是可加的。
     * min 用 0 表示「还没有样本」——实际 key 大小恒 &gt; 0，所以 0 不会与真实值混淆。
     */
    private void mergeValueSizeExtremes(KeyAnalysisStatsSnapshotDto merged, KeyAnalysisStatsSnapshotDto partial) {
        if (partial.getValueSizeSampleCount() <= 0) {
            return;
        }
        merged.setValueSizeSumBytes(merged.getValueSizeSumBytes() + partial.getValueSizeSumBytes());
        merged.setValueSizeSampleCount(merged.getValueSizeSampleCount() + partial.getValueSizeSampleCount());
        merged.setValueSizeMaxBytes(Math.max(merged.getValueSizeMaxBytes(), partial.getValueSizeMaxBytes()));
        if (partial.getValueSizeMinBytes() > 0
                && (merged.getValueSizeMinBytes() <= 0 || partial.getValueSizeMinBytes() < merged.getValueSizeMinBytes())) {
            merged.setValueSizeMinBytes(partial.getValueSizeMinBytes());
        }
    }

    public boolean hasDistributionData(long auditId) {
        KeyAnalysisStatsSnapshotDto snapshot = loadSnapshot(auditId);
        return snapshot != null && snapshot.isHasDistributionData();
    }

    /**
     * 任务结束时：若 MySQL 已有分布则清除 assist Redis 相关风险；否则尝试从 assist Redis 补一份。
     */
    public void finalizeAfterAnalysis(long appId, long auditId) {
        if (hasDistributionData(auditId)) {
            clearAssistRedisRisksInSnapshot(appId, auditId);
            return;
        }
        KeyAnalysisStatsSnapshotDto fromAssist = buildSnapshotFromAssistRedis(appId, auditId);
        if (fromAssist.isHasDistributionData()) {
            appKeyAnalysisStatsDao.upsertStats(appId, auditId, JSON.toJSONString(fromAssist));
        }
    }

    public void persistFromAssistRedis(long appId, long auditId) {
        if (hasDistributionData(auditId)) {
            return;
        }
        KeyAnalysisStatsSnapshotDto snapshot = buildSnapshotFromAssistRedis(appId, auditId);
        appKeyAnalysisStatsDao.upsertStats(appId, auditId, JSON.toJSONString(snapshot));
    }

    public boolean applyPersistedSnapshot(KeyAnalysisResultDto dto, long auditId) {
        KeyAnalysisStatsSnapshotDto snapshot = loadSnapshot(auditId);
        if (snapshot == null) {
            return false;
        }
        dto.getIdleKeyDistri().addAll(defaultList(snapshot.getIdleKeyDistri()));
        dto.getKeyTypeDistri().addAll(defaultList(snapshot.getKeyTypeDistri()));
        dto.getKeyTtlDistri().addAll(defaultList(snapshot.getKeyTtlDistri()));
        dto.getKeyValueSizeDistri().addAll(defaultList(snapshot.getKeyValueSizeDistri()));
        dto.setValueSizeMaxBytes(snapshot.getValueSizeMaxBytes());
        dto.setValueSizeMinBytes(snapshot.getValueSizeMinBytes());
        dto.setValueSizeSumBytes(snapshot.getValueSizeSumBytes());
        dto.setValueSizeSampleCount(snapshot.getValueSizeSampleCount());
        dto.getKeyTypeMemoryDistri().addAll(defaultList(snapshot.getKeyTypeMemoryDistri()));
        dto.getTopKeys().addAll(defaultList(snapshot.getTopKeys()));
        dto.getBigKeys().addAll(defaultList(snapshot.getBigKeys()));
        dto.getKeyPrefixTop().addAll(defaultList(snapshot.getKeyPrefixTop()));
        dto.setBigKeyStringBytes(snapshot.getBigKeyStringBytes());
        dto.setBigKeyCollectionElements(snapshot.getBigKeyCollectionElements());
        dto.setHasDistributionData(snapshot.isHasDistributionData());
        dto.setHasTypeMemoryData(snapshot.isHasTypeMemoryData());
        dto.setTopKeyFromMemoryScan(snapshot.isTopKeyFromMemoryScan());
        dto.setBigKeyCount(snapshot.getBigKeyCount());
        if (StringUtils.isNotBlank(snapshot.getAssistRedisEndpoint())) {
            dto.setAssistRedisEndpoint(snapshot.getAssistRedisEndpoint());
        }
        if (snapshot.getAnalysisRisks() != null && !snapshot.getAnalysisRisks().isEmpty()) {
            dto.getAnalysisRisks().addAll(snapshot.getAnalysisRisks());
            dto.setHasAnalysisRisk(true);
        }
        return dto.isHasDistributionData() || !dto.getTopKeys().isEmpty() || dto.getBigKeyCount() > 0;
    }

    private void clearAssistRedisRisksInSnapshot(long appId, long auditId) {
        KeyAnalysisStatsSnapshotDto snapshot = loadSnapshot(auditId);
        if (snapshot == null) {
            return;
        }
        if (snapshot.getAnalysisRisks() != null) {
            snapshot.getAnalysisRisks().clear();
        }
        appKeyAnalysisStatsDao.upsertStats(appId, auditId, JSON.toJSONString(snapshot));
    }

    private KeyAnalysisStatsSnapshotDto loadSnapshot(long auditId) {
        String statsJson = appKeyAnalysisStatsDao.getStatsJson(auditId);
        if (StringUtils.isBlank(statsJson)) {
            return null;
        }
        return JSON.parseObject(statsJson, KeyAnalysisStatsSnapshotDto.class);
    }

    private KeyAnalysisStatsSnapshotDto buildSnapshotFromAssistRedis(long appId, long auditId) {
        KeyAnalysisStatsSnapshotDto snapshot = new KeyAnalysisStatsSnapshotDto();
        snapshot.setAssistRedisEndpoint(assistRedisService.getAssistRedisEndpoint());

        String idleKeyResultKey = ConstUtils.getRedisServerIdleKey(appId, auditId);
        for (Tuple tuple : assistRedisService.zrangeWithScores(idleKeyResultKey, 0, -1)) {
            IdleTimeDistriEnum idleTimeDistriEnum = IdleTimeDistriEnum.getByValue(tuple.getElement());
            if (idleTimeDistriEnum == null) {
                continue;
            }
            snapshot.getIdleKeyDistri().add(new ParamCountDto(idleTimeDistriEnum.getInfo(), tuple.getScore(), ""));
        }

        String keyTypeResultKey = ConstUtils.getRedisServerTypeKey(appId, auditId);
        for (Tuple tuple : assistRedisService.zrangeWithScores(keyTypeResultKey, 0, -1)) {
            snapshot.getKeyTypeDistri().add(new ParamCountDto(tuple.getElement(), tuple.getScore(), ""));
        }

        String keyTtlResultKey = ConstUtils.getRedisServerTtlKey(appId, auditId);
        for (Tuple tuple : assistRedisService.zrangeWithScores(keyTtlResultKey, 0, -1)) {
            TtlTimeDistriEnum ttlTimeDistriEnum = TtlTimeDistriEnum.getByValue(tuple.getElement());
            if (ttlTimeDistriEnum == null) {
                continue;
            }
            snapshot.getKeyTtlDistri().add(new ParamCountDto(ttlTimeDistriEnum.getInfo(), tuple.getScore(), ""));
        }

        String keyValueSizeResultKey = ConstUtils.getRedisServerValueSizeKey(appId, auditId);
        for (Tuple tuple : assistRedisService.zrangeWithScores(keyValueSizeResultKey, 0, -1)) {
            ValueSizeDistriEnum valueSizeDistriEnum = ValueSizeDistriEnum.getByValue(tuple.getElement());
            if (valueSizeDistriEnum == null) {
                continue;
            }
            snapshot.getKeyValueSizeDistri().add(new ParamCountDto(valueSizeDistriEnum.getInfo(), tuple.getScore(), ""));
        }

        String keyTypeMemoryResultKey = ConstUtils.getRedisServerTypeMemoryKey(appId, auditId);
        for (Tuple tuple : assistRedisService.zrangeWithScores(keyTypeMemoryResultKey, 0, -1)) {
            snapshot.getKeyTypeMemoryDistri().add(new ParamCountDto(tuple.getElement(), tuple.getScore(), ""));
        }

        boolean topKeyFromMemoryScan = hasTopKeyMemoryScanData(appId, auditId);
        snapshot.setTopKeyFromMemoryScan(topKeyFromMemoryScan);
        for (InstanceBigKey key : resolveTopKeyList(appId, auditId)) {
            KeyAnalysisBigKeyDto row = new KeyAnalysisBigKeyDto();
            row.setInstance(key.getIp() + ":" + key.getPort());
            row.setKeyName(key.getBigKey());
            row.setType(key.getType());
            row.setLength(key.getLength());
            row.setSizeLabel(topKeyFromMemoryScan ? formatMemoryBytes(key.getLength()) : key.getLengthFormat());
            snapshot.getTopKeys().add(row);
        }

        List<InstanceBigKey> instanceBigKeyList = instanceBigKeyDao.getAppBigKeyList(appId, auditId, null);
        int bigKeyTotal = instanceBigKeyList != null ? instanceBigKeyList.size() : 0;
        snapshot.setBigKeyCount(bigKeyTotal);
        if (instanceBigKeyList != null) {
            int limit = Math.min(instanceBigKeyList.size(), 100);
            for (int i = 0; i < limit; i++) {
                InstanceBigKey key = instanceBigKeyList.get(i);
                KeyAnalysisBigKeyDto row = new KeyAnalysisBigKeyDto();
                row.setInstance(key.getIp() + ":" + key.getPort());
                row.setKeyName(key.getBigKey());
                row.setType(key.getType());
                row.setLength(key.getLength());
                row.setSizeLabel(formatBigKeyMetric(key));
                snapshot.getBigKeys().add(row);
            }
        }

        refreshDistributionFlags(snapshot);
        List<String> risks = assistRedisService.getKeyAnalysisRisks(appId, auditId);
        if (risks != null && !risks.isEmpty()) {
            snapshot.getAnalysisRisks().addAll(risks);
        }
        return snapshot;
    }

    private void refreshDistributionFlags(KeyAnalysisStatsSnapshotDto snapshot) {
        boolean hasDistributionData = !snapshot.getIdleKeyDistri().isEmpty()
                || !snapshot.getKeyTypeDistri().isEmpty()
                || !snapshot.getKeyTtlDistri().isEmpty()
                || !snapshot.getKeyValueSizeDistri().isEmpty()
                || !snapshot.getKeyTypeMemoryDistri().isEmpty()
                || !snapshot.getTopKeys().isEmpty()
                || snapshot.getBigKeyCount() > 0;
        snapshot.setHasDistributionData(hasDistributionData);
        if (!snapshot.getKeyTypeMemoryDistri().isEmpty()) {
            snapshot.setHasTypeMemoryData(true);
        }
    }

    private List<ParamCountDto> mergeParamCounts(List<ParamCountDto> base, List<ParamCountDto> extra) {
        Map<String, Double> merged = new LinkedHashMap<String, Double>();
        for (ParamCountDto item : defaultList(base)) {
            if (item == null || StringUtils.isBlank(item.getName())) {
                continue;
            }
            merged.put(item.getName(), item.getCount());
        }
        for (ParamCountDto item : defaultList(extra)) {
            if (item == null || StringUtils.isBlank(item.getName())) {
                continue;
            }
            merged.merge(item.getName(), item.getCount(), Double::sum);
        }
        List<ParamCountDto> result = new ArrayList<ParamCountDto>();
        for (Map.Entry<String, Double> entry : merged.entrySet()) {
            result.add(new ParamCountDto(entry.getKey(), entry.getValue(), ""));
        }
        return result;
    }

    private List<KeyAnalysisBigKeyDto> mergeTopKeys(List<KeyAnalysisBigKeyDto> base, List<KeyAnalysisBigKeyDto> extra) {
        List<KeyAnalysisBigKeyDto> all = new ArrayList<KeyAnalysisBigKeyDto>();
        all.addAll(defaultList(base));
        all.addAll(defaultList(extra));
        all.sort(Comparator.comparingLong(KeyAnalysisBigKeyDto::getLength).reversed());
        if (all.size() > 100) {
            return new ArrayList<KeyAnalysisBigKeyDto>(all.subList(0, 100));
        }
        return all;
    }

    private List<KeyPrefixStatDto> mergePrefixStats(List<KeyPrefixStatDto> base, List<KeyPrefixStatDto> extra) {
        Map<String, KeyPrefixStatDto> merged = new LinkedHashMap<String, KeyPrefixStatDto>();
        for (KeyPrefixStatDto item : defaultList(base)) mergePrefixItem(merged, item);
        for (KeyPrefixStatDto item : defaultList(extra)) mergePrefixItem(merged, item);
        Map<String, List<KeyPrefixStatDto>> byType = new LinkedHashMap<String, List<KeyPrefixStatDto>>();
        for (KeyPrefixStatDto item : merged.values()) {
            byType.computeIfAbsent(item.getType(), key -> new ArrayList<KeyPrefixStatDto>()).add(item);
        }
        List<KeyPrefixStatDto> result = new ArrayList<KeyPrefixStatDto>();
        for (List<KeyPrefixStatDto> items : byType.values()) {
            items.sort(Comparator.comparingLong(KeyPrefixStatDto::getCount).reversed());
            result.addAll(items.subList(0, Math.min(50, items.size())));
        }
        return result;
    }

    private void mergePrefixItem(Map<String, KeyPrefixStatDto> merged, KeyPrefixStatDto item) {
        if (item == null || StringUtils.isBlank(item.getType()) || StringUtils.isBlank(item.getPrefix())) return;
        String key = item.getType() + "\u0000" + item.getPrefix();
        KeyPrefixStatDto target = merged.get(key);
        if (target == null) {
            target = new KeyPrefixStatDto();
            target.setType(item.getType()); target.setPrefix(item.getPrefix());
            merged.put(key, target);
        }
        target.setCount(target.getCount() + item.getCount());
        target.setBytes(target.getBytes() + item.getBytes());
        target.setBytesLabel(formatMemoryBytes(target.getBytes()));
    }

    private List<InstanceBigKey> resolveTopKeyList(long appId, long auditId) {
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
                    return new ArrayList<InstanceBigKey>(parsed.subList(0, Math.min(100, parsed.size())));
                }
            } catch (Exception ignored) {
                // ignore legacy corrupt cache
            }
        }
        List<InstanceBigKey> fallback = instanceBigKeyDao.getTopAppBigKeyList(appId, auditId, 100);
        return fallback != null ? fallback : new ArrayList<InstanceBigKey>();
    }

    private List<InstanceBigKey> loadTopKeysFromZset(String topKeyRedisKey) {
        List<InstanceBigKey> list = new ArrayList<InstanceBigKey>();
        Set<Tuple> tuples = assistRedisService.zrevrangeWithScores(topKeyRedisKey, 0, 99);
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

    private boolean hasTopKeyMemoryScanData(long appId, long auditId) {
        String topKeyRedisKey = ConstUtils.getRedisServerTopKeyKey(appId, auditId);
        Set<Tuple> tuples = assistRedisService.zrevrangeWithScores(topKeyRedisKey, 0, 0);
        if (tuples != null && !tuples.isEmpty()) {
            return true;
        }
        return StringUtils.isNotBlank(assistRedisService.getWithNoSerialize(topKeyRedisKey));
    }

    public static String formatMemoryBytes(long bytes) {
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

    public static String formatBigKeyMetric(InstanceBigKey key) {
        if (key == null) return "-";
        return "string".equalsIgnoreCase(key.getType())
                ? formatMemoryBytes(key.getLength())
                : key.getLength() + " 个元素";
    }

    private static <T> List<T> defaultList(List<T> list) {
        return list != null ? list : new ArrayList<T>();
    }
}
