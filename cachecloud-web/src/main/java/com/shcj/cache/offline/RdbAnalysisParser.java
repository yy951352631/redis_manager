package com.shcj.cache.offline;

import com.moilioncircle.redis.replicator.Configuration;
import com.moilioncircle.redis.replicator.FileType;
import com.moilioncircle.redis.replicator.RedisReplicator;
import com.moilioncircle.redis.replicator.Replicator;
import com.moilioncircle.redis.replicator.event.Event;
import com.moilioncircle.redis.replicator.event.EventListener;
import com.moilioncircle.redis.replicator.rdb.datatype.ExpiredType;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyStringValueHash;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyStringValueList;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyStringValueSet;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyStringValueString;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyStringValueZSet;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyValuePair;
import com.moilioncircle.redis.replicator.rdb.datatype.ZSetEntry;
import com.shcj.cache.task.constant.TtlTimeDistriEnum;
import com.shcj.cache.task.constant.ValueSizeDistriEnum;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisBigKeyDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisStatsSnapshotDto;
import com.shcj.cache.web.controller.api.dto.KeyPrefixStatDto;
import com.shcj.cache.web.controller.api.dto.ParamCountDto;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * 离线 RDB 解析：流式遍历文件事件，在 JVM 内累加分布，结果结构与在线键值分析完全一致。
 *
 * <p>不把数据灌进 Redis，因此不占用辅助实例内存，超大文件也能处理；代价是体积只能估算——
 * RDB 里没有 MEMORY USAGE，这里按"key 长度 + 各元素长度 + 每元素固定开销"估算，
 * 与在线分析用 MEMORY USAGE 得到的绝对值会有差异，但分桶趋势一致。
 *
 * <p>空闲时间在 RDB 中不存在，因此离线结果没有空闲键分布，由调用方写入风险提示。
 */
@Component
public class RdbAnalysisParser {

    private static final Logger logger = LoggerFactory.getLogger(RdbAnalysisParser.class);

    /** Top key 保留数量，与在线分析一致 */
    private static final int TOP_KEY_LIMIT = 10;

    /** 前缀统计保留数量 */
    private static final int PREFIX_LIMIT = 20;

    /** 前缀累计的最大基数，超过后不再新增前缀，避免全是随机 key 时把内存打爆 */
    private static final int PREFIX_CARDINALITY_LIMIT = 200000;

    /** 集合类每个元素的估算固定开销（字节），近似 Redis 内部结构的指针与元数据 */
    private static final int ELEMENT_OVERHEAD = 16;

    /** 每个 key 自身的估算固定开销（字节） */
    private static final int KEY_OVERHEAD = 48;

    /** 每处理这么多 key 回调一次进度 */
    private static final int PROGRESS_STEP = 10000;

    public KeyAnalysisStatsSnapshotDto parse(File file, long bigKeyStringBytes, long bigKeyCollectionElements,
                                             LongConsumer keyCountReporter) throws Exception {
        final Accumulator acc = new Accumulator(bigKeyStringBytes, bigKeyCollectionElements);
        Configuration configuration = Configuration.defaultSetting();
        // 离线文件解析不需要重试
        configuration.setRetries(0);

        Replicator replicator = new RedisReplicator(file, FileType.RDB, configuration);
        replicator.addEventListener(new EventListener() {
            @Override
            public void onEvent(Replicator r, Event event) {
                if (!(event instanceof KeyValuePair)) {
                    return;
                }
                try {
                    acc.accept((KeyValuePair<?, ?>) event);
                } catch (Exception e) {
                    // 单个 key 解析失败不应中断整份文件
                    acc.skipped++;
                }
                if (keyCountReporter != null && acc.total % PROGRESS_STEP == 0) {
                    keyCountReporter.accept(acc.total);
                }
            }
        });
        try {
            replicator.open();
        } finally {
            try {
                replicator.close();
            } catch (Exception ignore) {
                // 关闭失败不影响已累加的结果
            }
        }
        if (keyCountReporter != null) {
            keyCountReporter.accept(acc.total);
        }
        logger.info("rdb parsed keys={} skipped={} file={}", acc.total, acc.skipped, file.getName());
        return acc.toSnapshot();
    }

    /** 遍历过程中的累加器，只持有分桶计数与 TopN，内存占用与 key 总数无关 */
    private static final class Accumulator {

        private final long bigKeyStringBytes;
        private final long bigKeyCollectionElements;

        private long total;
        private long skipped;
        private final Map<String, Long> typeCount = new HashMap<String, Long>();
        private final Map<String, Long> typeMemory = new HashMap<String, Long>();
        private final Map<TtlTimeDistriEnum, Long> ttlCount = new HashMap<TtlTimeDistriEnum, Long>();
        private final Map<ValueSizeDistriEnum, Long> sizeCount = new HashMap<ValueSizeDistriEnum, Long>();
        private final Map<String, PrefixAccumulator> prefixStats = new HashMap<String, PrefixAccumulator>();
        private final List<TopKey> topKeys = new ArrayList<TopKey>();
        private final List<TopKey> bigKeys = new ArrayList<TopKey>();
        /** 键值大小极值，口径与在线分析一致；min 用 0 作「尚无样本」的哨兵 */
        private long sizeMax;
        private long sizeMin;
        private long sizeSum;
        private long sizeSamples;

        Accumulator(long bigKeyStringBytes, long bigKeyCollectionElements) {
            this.bigKeyStringBytes = bigKeyStringBytes;
            this.bigKeyCollectionElements = bigKeyCollectionElements;
        }

        void accept(KeyValuePair<?, ?> pair) {
            String key = asString(pair.getKey());
            Sized sized = measure(pair);
            if (sized == null) {
                skipped++;
                return;
            }
            total++;
            merge(typeCount, sized.type, 1L);
            merge(typeMemory, sized.type, sized.bytes);
            applyTtl(pair);

            ValueSizeDistriEnum bucket = ValueSizeDistriEnum.getRightSizeBetween(sized.bytes);
            if (bucket != null) {
                Long current = sizeCount.get(bucket);
                sizeCount.put(bucket, current == null ? 1L : current + 1);
            }
            trackSizeExtremes(sized.bytes);
            trackPrefix(key, sized);
            trackTop(topKeys, key, sized);
            if (isBigKey(sized)) {
                trackTop(bigKeys, key, sized);
            }
        }

        private void trackSizeExtremes(long bytes) {
            if (bytes <= 0) {
                return;
            }
            sizeSamples++;
            sizeSum += bytes;
            if (bytes > sizeMax) {
                sizeMax = bytes;
            }
            if (sizeMin <= 0 || bytes < sizeMin) {
                sizeMin = bytes;
            }
        }

        private boolean isBigKey(Sized sized) {
            if ("string".equals(sized.type)) {
                return sized.bytes >= bigKeyStringBytes;
            }
            return sized.elements >= bigKeyCollectionElements;
        }

        private void applyTtl(KeyValuePair<?, ?> pair) {
            ExpiredType expiredType = pair.getExpiredType();
            TtlTimeDistriEnum distri;
            if (expiredType == null || expiredType == ExpiredType.NONE || pair.getExpiredValue() == null) {
                distri = TtlTimeDistriEnum.BETWEEN_PERSIST_HOURS;
            } else {
                long expireAtMs = expiredType == ExpiredType.SECOND
                        ? pair.getExpiredValue() * 1000L : pair.getExpiredValue();
                long ttlSeconds = (expireAtMs - System.currentTimeMillis()) / 1000L;
                // 已过期但尚未被清理的 key，按最短存活区间计入而不是丢弃
                distri = TtlTimeDistriEnum.getRightTtlDistri(Math.max(ttlSeconds, 0L) / 3600);
            }
            if (distri != null) {
                Long current = ttlCount.get(distri);
                ttlCount.put(distri, current == null ? 1L : current + 1);
            }
        }

        private void trackPrefix(String key, Sized sized) {
            String prefix = extractPrefix(key);
            String mapKey = sized.type + " " + prefix;
            PrefixAccumulator accumulator = prefixStats.get(mapKey);
            if (accumulator == null) {
                if (prefixStats.size() >= PREFIX_CARDINALITY_LIMIT) {
                    return;
                }
                accumulator = new PrefixAccumulator(sized.type, prefix);
                prefixStats.put(mapKey, accumulator);
            }
            accumulator.count++;
            accumulator.bytes += sized.bytes;
        }

        /** 维持一个大小为 TOP_KEY_LIMIT 的最小堆语义列表：list.get(0) 始终是当前最小的 */
        private void trackTop(List<TopKey> list, String key, Sized sized) {
            if (list.size() < TOP_KEY_LIMIT) {
                list.add(new TopKey(key, sized));
                sortAscending(list);
                return;
            }
            if (sized.bytes > list.get(0).bytes) {
                list.set(0, new TopKey(key, sized));
                sortAscending(list);
            }
        }

        private void sortAscending(List<TopKey> list) {
            list.sort(new Comparator<TopKey>() {
                @Override
                public int compare(TopKey a, TopKey b) {
                    return Long.compare(a.bytes, b.bytes);
                }
            });
        }

        KeyAnalysisStatsSnapshotDto toSnapshot() {
            KeyAnalysisStatsSnapshotDto dto = new KeyAnalysisStatsSnapshotDto();
            dto.setBigKeyStringBytes(bigKeyStringBytes);
            dto.setBigKeyCollectionElements(bigKeyCollectionElements);
            dto.setHasTypeMemoryData(!typeMemory.isEmpty());
            dto.setTopKeyFromMemoryScan(false);
            dto.setBigKeyCount(bigKeys.size());

            dto.setKeyTypeDistri(toParamCounts(typeCount));
            dto.setKeyTypeMemoryDistri(toParamCounts(typeMemory));

            Map<String, Long> ttl = new LinkedHashMap<String, Long>();
            for (Map.Entry<TtlTimeDistriEnum, Long> e : ttlCount.entrySet()) {
                ttl.put(e.getKey().getValue(), e.getValue());
            }
            dto.setKeyTtlDistri(toParamCounts(ttl));

            Map<String, Long> size = new LinkedHashMap<String, Long>();
            for (Map.Entry<ValueSizeDistriEnum, Long> e : sizeCount.entrySet()) {
                size.put(e.getKey().getValue(), e.getValue());
            }
            dto.setKeyValueSizeDistri(toParamCounts(size));
            dto.setValueSizeMaxBytes(sizeMax);
            dto.setValueSizeMinBytes(sizeMin);
            dto.setValueSizeSumBytes(sizeSum);
            dto.setValueSizeSampleCount(sizeSamples);

            dto.setTopKeys(toBigKeyDtos(topKeys));
            dto.setBigKeys(toBigKeyDtos(bigKeys));
            dto.setKeyPrefixTop(toPrefixDtos());
            dto.setHasDistributionData(total > 0);
            return dto;
        }

        private List<ParamCountDto> toParamCounts(Map<String, Long> source) {
            List<ParamCountDto> list = new ArrayList<ParamCountDto>(source.size());
            for (Map.Entry<String, Long> entry : source.entrySet()) {
                ParamCountDto dto = new ParamCountDto();
                dto.setName(entry.getKey());
                dto.setCount(entry.getValue());
                list.add(dto);
            }
            list.sort(new Comparator<ParamCountDto>() {
                @Override
                public int compare(ParamCountDto a, ParamCountDto b) {
                    return Double.compare(b.getCount(), a.getCount());
                }
            });
            return list;
        }

        private List<KeyAnalysisBigKeyDto> toBigKeyDtos(List<TopKey> source) {
            List<TopKey> sorted = new ArrayList<TopKey>(source);
            sorted.sort(new Comparator<TopKey>() {
                @Override
                public int compare(TopKey a, TopKey b) {
                    return Long.compare(b.bytes, a.bytes);
                }
            });
            List<KeyAnalysisBigKeyDto> list = new ArrayList<KeyAnalysisBigKeyDto>(sorted.size());
            for (TopKey top : sorted) {
                KeyAnalysisBigKeyDto dto = new KeyAnalysisBigKeyDto();
                dto.setInstance("离线文件");
                dto.setKeyName(top.key);
                dto.setType(top.type);
                dto.setSizeLabel(humanBytes(top.bytes));
                dto.setLength(top.elements);
                list.add(dto);
            }
            return list;
        }

        private List<KeyPrefixStatDto> toPrefixDtos() {
            List<PrefixAccumulator> sorted = new ArrayList<PrefixAccumulator>(prefixStats.values());
            sorted.sort(new Comparator<PrefixAccumulator>() {
                @Override
                public int compare(PrefixAccumulator a, PrefixAccumulator b) {
                    return Long.compare(b.bytes, a.bytes);
                }
            });
            List<KeyPrefixStatDto> list = new ArrayList<KeyPrefixStatDto>();
            for (PrefixAccumulator p : sorted.subList(0, Math.min(PREFIX_LIMIT, sorted.size()))) {
                KeyPrefixStatDto dto = new KeyPrefixStatDto();
                dto.setType(p.type);
                dto.setPrefix(p.prefix);
                dto.setCount(p.count);
                dto.setBytes(p.bytes);
                dto.setBytesLabel(humanBytes(p.bytes));
                list.add(dto);
            }
            return list;
        }

        private static void merge(Map<String, Long> map, String key, long delta) {
            Long current = map.get(key);
            map.put(key, current == null ? delta : current + delta);
        }
    }

    // #region 体积估算
    private static Sized measure(KeyValuePair<?, ?> pair) {
        String key = asString(pair.getKey());
        long keyBytes = utf8Length(key) + KEY_OVERHEAD;

        if (pair instanceof KeyStringValueString) {
            byte[] value = ((KeyStringValueString) pair).getValue();
            long len = value == null ? 0 : value.length;
            return new Sized("string", keyBytes + len, len);
        }
        if (pair instanceof KeyStringValueHash) {
            Map<byte[], byte[]> value = ((KeyStringValueHash) pair).getValue();
            long bytes = keyBytes;
            long elements = 0;
            if (value != null) {
                for (Map.Entry<byte[], byte[]> e : value.entrySet()) {
                    bytes += length(e.getKey()) + length(e.getValue()) + ELEMENT_OVERHEAD;
                    elements++;
                }
            }
            return new Sized("hash", bytes, elements);
        }
        if (pair instanceof KeyStringValueList) {
            return collection("list", keyBytes, ((KeyStringValueList) pair).getValue());
        }
        if (pair instanceof KeyStringValueSet) {
            Set<byte[]> value = ((KeyStringValueSet) pair).getValue();
            return collection("set", keyBytes, value);
        }
        if (pair instanceof KeyStringValueZSet) {
            Set<ZSetEntry> value = ((KeyStringValueZSet) pair).getValue();
            long bytes = keyBytes;
            long elements = 0;
            if (value != null) {
                for (ZSetEntry entry : value) {
                    bytes += length(entry.getElement()) + 8 + ELEMENT_OVERHEAD;
                    elements++;
                }
            }
            return new Sized("zset", bytes, elements);
        }
        // stream / module 等类型只计 key 本身，避免把未知结构算错
        return new Sized("other", keyBytes, 0);
    }

    private static Sized collection(String type, long keyBytes, Iterable<byte[]> values) {
        long bytes = keyBytes;
        long elements = 0;
        if (values != null) {
            for (byte[] item : values) {
                bytes += length(item) + ELEMENT_OVERHEAD;
                elements++;
            }
        }
        return new Sized(type, bytes, elements);
    }

    private static long length(byte[] value) {
        return value == null ? 0 : value.length;
    }

    private static long utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
    // #endregion

    private static String asString(Object key) {
        if (key == null) {
            return "";
        }
        if (key instanceof byte[]) {
            return new String((byte[]) key, StandardCharsets.UTF_8);
        }
        return String.valueOf(key);
    }

    /** 与在线分析 RedisServerKeyCombinedAnalysisTask.extractPrefix 保持同一口径 */
    static String extractPrefix(String key) {
        if (StringUtils.isBlank(key)) {
            return "(empty)";
        }
        int end = key.length();
        for (char separator : new char[]{':', '_', '-', '.', '/'}) {
            int index = key.indexOf(separator);
            if (index > 0 && index < end) {
                end = index;
            }
        }
        if (end == key.length()) {
            for (int i = 1; i < key.length(); i++) {
                if (Character.isDigit(key.charAt(i))) {
                    end = i;
                    break;
                }
            }
        }
        String prefix = key.substring(0, Math.min(end, 32));
        return prefix.length() <= 256 ? prefix : prefix.substring(0, 253) + "...";
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.2fK", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.2fM", bytes / 1024.0 / 1024.0);
        }
        return String.format("%.2fG", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private static final class Sized {
        final String type;
        final long bytes;
        final long elements;

        Sized(String type, long bytes, long elements) {
            this.type = type;
            this.bytes = bytes;
            this.elements = elements;
        }
    }

    private static final class TopKey {
        final String key;
        final String type;
        final long bytes;
        final long elements;

        TopKey(String key, Sized sized) {
            this.key = key;
            this.type = sized.type;
            this.bytes = sized.bytes;
            this.elements = sized.elements;
        }
    }

    private static final class PrefixAccumulator {
        final String type;
        final String prefix;
        long count;
        long bytes;

        PrefixAccumulator(String type, String prefix) {
            this.type = type;
            this.prefix = prefix;
        }
    }
}
