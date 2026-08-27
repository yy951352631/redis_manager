package com.shcj.cache.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 采集时间差与 CPU 使用率的计算测试。
 *
 * <p>原实现把 yyyyMMddHHmm 当普通整数做算术：{@code (t2/100 - t1/100) * 60}。
 * 除以 100 抹掉的是分钟位而不是秒位，同一小时内的两次采集相减恒为 0，
 * CPU 使用率因此在 60 分钟里有 59 分钟显示为 0，只有跨小时那一分钟碰巧算对。</p>
 */
public class CollectTimeUtilTest {

    @Test
    public void 相邻两分钟相差六十秒() {
        assertEquals(60L, CollectTimeUtil.secondsBetween(202608270616L, 202608270617L));
    }

    @Test
    public void 同一小时内不再恒为零() {
        // 这正是原实现失效的区间：整点内的任意两次采集都被算成 0 秒
        assertEquals(60L, CollectTimeUtil.secondsBetween(202608270601L, 202608270602L));
        assertEquals(1740L, CollectTimeUtil.secondsBetween(202608270601L, 202608270630L));
        assertEquals(3480L, CollectTimeUtil.secondsBetween(202608270601L, 202608270659L));
    }

    @Test
    public void 跨小时跨天跨月跨年() {
        assertEquals(60L, CollectTimeUtil.secondsBetween(202608270659L, 202608270700L));
        assertEquals(60L, CollectTimeUtil.secondsBetween(202608272359L, 202608280000L));
        assertEquals(60L, CollectTimeUtil.secondsBetween(202608312359L, 202609010000L));
        assertEquals(60L, CollectTimeUtil.secondsBetween(202612312359L, 202701010000L));
    }

    @Test
    public void 顺序颠倒或非法输入返回零() {
        assertEquals(0L, CollectTimeUtil.secondsBetween(202608270617L, 202608270616L));
        assertEquals(0L, CollectTimeUtil.secondsBetween(202608270617L, 202608270617L));
        assertEquals(0L, CollectTimeUtil.secondsBetween(0L, 202608270617L));
        assertEquals(0L, CollectTimeUtil.secondsBetween(202608270617L, 0L));
    }

    @Test
    public void 使用率按累计秒数增量与跨度换算() {
        // 一分钟内消耗 0.1348 CPU 秒 -> 0.2%
        assertEquals(0.2D, CollectTimeUtil.cpuUsePercent(0.1348D, 60L), 1e-9);
        // 满打满算一个核
        assertEquals(100.0D, CollectTimeUtil.cpuUsePercent(60D, 60L), 1e-9);
        // 多核可以超过 100%
        assertEquals(250.0D, CollectTimeUtil.cpuUsePercent(150D, 60L), 1e-9);
    }

    @Test
    public void 小数点后保留一位() {
        assertEquals(0.3D, CollectTimeUtil.cpuUsePercent(0.1648D, 60L), 1e-9);
        assertEquals(0.1D, CollectTimeUtil.cpuUsePercent(0.06D, 60L), 1e-9);
    }

    @Test
    public void 实例重启导致计数器归零时不给结论() {
        assertEquals(0.0D, CollectTimeUtil.cpuUsePercent(-420D, 60L), 1e-9);
    }

    @Test
    public void 跨度为零时不做除法() {
        assertEquals(0.0D, CollectTimeUtil.cpuUsePercent(1D, 0L), 1e-9);
    }
}
