package com.shcj.cache.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.shcj.cache.stats.instance.impl.InstanceStatsCenterImpl;
import com.shcj.cache.util.ConstUtils;

/**
 * 「读/写命令统计」图表的汇总口径。
 *
 * <p>盯的是空闲节点：只有平台自己的 ping/info 时，两条曲线必须是 0 而不是整张图空白，
 * 否则「没有业务流量」和「采集坏了」在界面上分不出来。
 */
public class ReadWriteCommandStatsTest {

    private Long sum(Map<String, Object> diffMap, boolean write) {
        return InstanceStatsCenterImpl.sumCommandStats(diffMap, write);
    }

    private Map<String, Object> diff(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    /** 空闲节点：只有平台采集自身的 ping/info，读写都应该是实打实的 0 */
    @Test
    public void idleNodeReportsZeroInsteadOfNoData() {
        Map<String, Object> diffMap = diff("cmdstat_ping", 21L, "cmdstat_info", 3L, "collect_time", 202609022000L);
        assertEquals(Long.valueOf(0L), sum(diffMap, false));
        assertEquals(Long.valueOf(0L), sum(diffMap, true));
    }

    /** 这一分钟压根没采到 commandstats，才是真的"无数据"，跳过采集点 */
    @Test
    public void missingCommandStatsIsSkipped() {
        Map<String, Object> diffMap = diff("connected_clients", 12L, "collect_time", 202609022000L);
        assertNull(sum(diffMap, false));
        assertNull(sum(diffMap, true));
    }

    /** 有业务流量时按读/写归类求和，ping/info 这类不碰键空间的命令不计入 */
    @Test
    public void classifiesBusinessTrafficAndExcludesNonKeyspace() {
        Map<String, Object> diffMap = diff(
                "cmdstat_get", 100L,
                "cmdstat_hget", 4L,
                "cmdstat_set", 700L,
                "cmdstat_setex", 42L,
                "cmdstat_ping", 592L,
                "cmdstat_info", 58L);
        assertEquals(Long.valueOf(104L), sum(diffMap, false));
        assertEquals(Long.valueOf(742L), sum(diffMap, true));
    }

    /**
     * 副本上的写是复制过来的，不是客户端写：主从三节点 cmdstat_setex 计数完全相同，
     * 照实求和会把写放大成副本数倍。读不受影响，副本上的 get 是真实客户端读。
     */
    @Test
    public void replicaWritesAreNotCountedButReplicaReadsAre() {
        Map<String, Object> replica = diff(
                "cmdstat_setex", 700L,
                "cmdstat_get", 104L,
                ConstUtils.INSTANCE_ROLE_MASTER, 0L);
        assertEquals(Long.valueOf(0L), sum(replica, true));
        assertEquals(Long.valueOf(104L), sum(replica, false));

        Map<String, Object> master = diff(
                "cmdstat_setex", 700L,
                "cmdstat_get", 104L,
                ConstUtils.INSTANCE_ROLE_MASTER, 1L);
        assertEquals(Long.valueOf(700L), sum(master, true));
        assertEquals(Long.valueOf(104L), sum(master, false));
    }

    /** 老数据没有角色字段，按 master 处理，历史曲线不变 */
    @Test
    public void legacyPointWithoutRoleStillCountsWrites() {
        Map<String, Object> diffMap = diff("cmdstat_setex", 700L);
        assertEquals(Long.valueOf(700L), sum(diffMap, true));
    }

    /** 未知命令兜底算读：写曲线偏低比把读误计成写更好判断 */
    @Test
    public void unknownCommandCountsAsRead() {
        Map<String, Object> diffMap = diff("cmdstat_somenewcmd", 9L);
        assertEquals(Long.valueOf(9L), sum(diffMap, false));
        assertEquals(Long.valueOf(0L), sum(diffMap, true));
    }
}
