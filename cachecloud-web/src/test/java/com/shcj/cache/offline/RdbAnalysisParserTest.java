package com.shcj.cache.offline;

import com.shcj.cache.web.controller.api.dto.KeyAnalysisBigKeyDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisStatsSnapshotDto;
import com.shcj.cache.web.controller.api.dto.ParamCountDto;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线 RDB 解析的单元测试。
 *
 * <p>样例文件由 src/test/resources/offline/sample-dump.rdb 提供，内容是一份包含
 * string/hash/list/set/zset 五种类型、含 TTL 与一个 300KB 大 key 的真实 dump。
 */
public class RdbAnalysisParserTest {

    private File sampleRdb() {
        java.net.URL url = getClass().getClassLoader().getResource("offline/sample-dump.rdb");
        Assumptions.assumeTrue(url != null, "缺少样例 RDB 文件");
        return new File(url.getFile());
    }

    private Map<String, Double> asMap(java.util.List<ParamCountDto> list) {
        Map<String, Double> map = new HashMap<String, Double>();
        for (ParamCountDto dto : list) {
            map.put(dto.getName(), dto.getCount());
        }
        return map;
    }

    @Test
    public void 解析出全部键并给出各类型计数() throws Exception {
        RdbAnalysisParser parser = new RdbAnalysisParser();
        final AtomicLong reported = new AtomicLong();
        KeyAnalysisStatsSnapshotDto snapshot = parser.parse(sampleRdb(), 100 * 1024L, 50000L, reported::set);

        assertNotNull(snapshot);
        assertTrue(snapshot.isHasDistributionData(), "应解析出分布数据");

        Map<String, Double> types = asMap(snapshot.getKeyTypeDistri());
        // 样例包含 4 个 string（user:1001/user:1002/session:abc/session:def/big:blob 共 5 个）
        assertTrue(types.containsKey("string"), "应识别出 string 类型");
        assertTrue(types.containsKey("hash"), "应识别出 hash 类型");
        assertTrue(types.containsKey("list"), "应识别出 list 类型");
        assertTrue(types.containsKey("set"), "应识别出 set 类型");
        assertTrue(types.containsKey("zset"), "应识别出 zset 类型");

        double total = 0;
        for (Double count : types.values()) {
            total += count;
        }
        assertEquals(reported.get(), (long) total, "类型计数之和应等于回调上报的 key 总数");
    }

    @Test
    public void TTL分布区分不过期与带过期时间的键() throws Exception {
        RdbAnalysisParser parser = new RdbAnalysisParser();
        KeyAnalysisStatsSnapshotDto snapshot = parser.parse(sampleRdb(), 100 * 1024L, 50000L, null);

        Map<String, Double> ttl = asMap(snapshot.getKeyTtlDistri());
        assertFalse(ttl.isEmpty(), "TTL 分布不应为空");
        // session:* 两个键带 TTL，其余不过期，因此至少有两个不同的分桶
        assertTrue(ttl.size() >= 2, "应同时存在不过期与带过期的分桶");
    }

    @Test
    public void 大key按阈值识别且Top列表按体积倒序() throws Exception {
        RdbAnalysisParser parser = new RdbAnalysisParser();
        // 阈值设成 100KB，样例里 300KB 的 big:blob 应被判为大 key
        KeyAnalysisStatsSnapshotDto snapshot = parser.parse(sampleRdb(), 100 * 1024L, 50000L, null);

        assertTrue(snapshot.getBigKeyCount() >= 1, "应至少识别出一个大 key");
        assertFalse(snapshot.getTopKeys().isEmpty(), "Top key 列表不应为空");

        KeyAnalysisBigKeyDto first = snapshot.getTopKeys().get(0);
        assertEquals("big:blob", first.getKeyName(), "体积最大的应当是 300KB 的 big:blob");
        assertEquals("string", first.getType());

        boolean bigKeyHit = false;
        for (KeyAnalysisBigKeyDto dto : snapshot.getBigKeys()) {
            if ("big:blob".equals(dto.getKeyName())) {
                bigKeyHit = true;
            }
        }
        assertTrue(bigKeyHit, "大 key 列表应包含 big:blob");
    }

    @Test
    public void 阈值调高后不再判定为大key() throws Exception {
        RdbAnalysisParser parser = new RdbAnalysisParser();
        // 阈值 1MB，300KB 的键不应再算大 key
        KeyAnalysisStatsSnapshotDto snapshot = parser.parse(sampleRdb(), 1024 * 1024L, 50000L, null);
        assertEquals(0, snapshot.getBigKeyCount(), "阈值高于最大键体积时不应有大 key");
    }

    @Test
    public void 大小极值与Top列表和类型内存互相自洽() throws Exception {
        RdbAnalysisParser parser = new RdbAnalysisParser();
        KeyAnalysisStatsSnapshotDto snapshot = parser.parse(sampleRdb(), 100 * 1024L, 50000L, null);

        long samples = snapshot.getValueSizeSampleCount();
        assertTrue(samples > 0, "应采集到大小样本");

        // 最大值必须与 Top 列表第一名的体积一致——两处用的是同一批 bytes，对不上说明有一边算错了。
        // 注意不能拿 getLength() 比：那是原始元素数/值长度，string 类型不含 key 名与对象开销。
        assertEquals(RdbAnalysisParser.humanBytes(snapshot.getValueSizeMaxBytes()),
                snapshot.getTopKeys().get(0).getSizeLabel(),
                "极值的最大值应等于 Top key 的体积");

        // 总量必须与各类型内存之和一致
        double typeMemoryTotal = 0;
        for (ParamCountDto dto : snapshot.getKeyTypeMemoryDistri()) {
            typeMemoryTotal += dto.getCount();
        }
        assertEquals((long) typeMemoryTotal, snapshot.getValueSizeSumBytes(),
                "极值的总量应等于各类型内存之和");

        assertTrue(snapshot.getValueSizeMinBytes() > 0, "最小值不应为 0 哨兵值");
        assertTrue(snapshot.getValueSizeMinBytes() <= snapshot.getValueSizeMaxBytes(), "最小值不应大于最大值");

        long avg = snapshot.getValueSizeSumBytes() / samples;
        assertTrue(avg >= snapshot.getValueSizeMinBytes() && avg <= snapshot.getValueSizeMaxBytes(),
                "平均值应落在极值区间内");
    }

    @Test
    public void 前缀统计按业务前缀聚合() throws Exception {
        RdbAnalysisParser parser = new RdbAnalysisParser();
        KeyAnalysisStatsSnapshotDto snapshot = parser.parse(sampleRdb(), 100 * 1024L, 50000L, null);
        assertFalse(snapshot.getKeyPrefixTop().isEmpty(), "前缀统计不应为空");
    }

    @Test
    public void 前缀提取与在线分析口径一致() {
        assertEquals("user", RdbAnalysisParser.extractPrefix("user:1001"));
        assertEquals("order", RdbAnalysisParser.extractPrefix("order_20260821"));
        assertEquals("cache", RdbAnalysisParser.extractPrefix("cache.item.1"));
        assertEquals("api", RdbAnalysisParser.extractPrefix("api/v1/users"));
        // 无分隔符时切到第一个数字
        assertEquals("key", RdbAnalysisParser.extractPrefix("key2091"));
        assertEquals("(empty)", RdbAnalysisParser.extractPrefix(""));
        assertEquals("(empty)", RdbAnalysisParser.extractPrefix(null));
    }

    @Test
    public void 体积格式化按量级选择单位() {
        assertEquals("512B", RdbAnalysisParser.humanBytes(512));
        assertEquals("1.00K", RdbAnalysisParser.humanBytes(1024));
        assertEquals("1.00M", RdbAnalysisParser.humanBytes(1024 * 1024));
        assertEquals("1.00G", RdbAnalysisParser.humanBytes(1024L * 1024 * 1024));
    }
}
