package com.shcj.cache.risk.evaluator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowthTrendTest {

    private static final long DAY = 24 * 3600 * 1000L;
    private static final long T0 = 1_800_000_000_000L;

    /** 每天一个点，值按给定数组 */
    private List<double[]> daily(double... values) {
        List<double[]> points = new ArrayList<double[]>();
        for (int i = 0; i < values.length; i++) {
            points.add(new double[]{T0 + i * DAY, values[i]});
        }
        return points;
    }

    @Test
    void 完美线性增长的斜率等于每天的增量() {
        GrowthTrend trend = GrowthTrend.fit(daily(100, 110, 120, 130, 140, 150, 160));
        assertNotNull(trend);
        assertEquals(10D, trend.getSlopePerDay(), 1e-6, "每天涨 10");
        assertEquals(130D, trend.getMeanValue(), 1e-6);
        assertEquals(100D, trend.getFirstValue(), 1e-6);
        assertEquals(160D, trend.getLastValue(), 1e-6);
        assertEquals(6D, trend.getWindowDays(), 1e-6);
    }

    @Test
    void 日均增长率以窗口均值为基准() {
        GrowthTrend trend = GrowthTrend.fit(daily(100, 110, 120, 130, 140, 150, 160));
        assertNotNull(trend);
        // 10 / 130 * 100
        assertEquals(7.6923D, trend.dailyPercent(), 1e-3);
    }

    @Test
    void 窗口正中的单点异常对斜率完全没有影响() {
        // 最小二乘的斜率只取决于 Σ(t-均值t)·v，正中那点的 (t-均值t) 恰为 0，
        // 因此无论它抖到多离谱都不会改变斜率——这正是不用「末值-首值」的价值所在
        GrowthTrend spiky = GrowthTrend.fit(daily(100, 110, 120, 900, 140, 150, 160));
        GrowthTrend clean = GrowthTrend.fit(daily(100, 110, 120, 130, 140, 150, 160));
        assertNotNull(spiky);
        assertNotNull(clean);
        assertEquals(clean.getSlopePerDay(), spiky.getSlopePerDay(), 1e-9);
    }

    @Test
    void 偏离中心的单点异常对斜率的影响可精确量化() {
        // 把抖动挪到倒数第二个点（相对中心 dt = +2 天）
        GrowthTrend spiky = GrowthTrend.fit(daily(100, 110, 120, 130, 140, 900, 160));
        GrowthTrend clean = GrowthTrend.fit(daily(100, 110, 120, 130, 140, 150, 160));
        assertNotNull(spiky);
        assertNotNull(clean);

        // 最小二乘下，单点偏差 delta 对斜率的贡献恰为 delta * dt / Σdt²。
        // 7 个等距点的 dt 为 -3..3，Σdt² = 28；该点 dt = 2。
        // 也就是说异常值被按 2/28 的权重稀释，而不是被整段采信。
        double delta = 900D - 150D;
        double expected = clean.getSlopePerDay() + delta * 2D / 28D;
        assertEquals(expected, spiky.getSlopePerDay(), 1e-6);
        assertTrue(spiky.getSlopePerDay() > clean.getSlopePerDay(), "异常点抬高了斜率");
    }

    @Test
    void 下降趋势得到负增长率() {
        GrowthTrend trend = GrowthTrend.fit(daily(200, 190, 180, 170, 160, 150));
        assertNotNull(trend);
        assertTrue(trend.getSlopePerDay() < 0);
        assertTrue(trend.dailyPercent() < 0);
    }

    @Test
    void 持平趋势增长率为零() {
        GrowthTrend trend = GrowthTrend.fit(daily(100, 100, 100, 100, 100, 100));
        assertNotNull(trend);
        assertEquals(0D, trend.getSlopePerDay(), 1e-9);
        assertEquals(0D, trend.dailyPercent(), 1e-9);
    }

    @Test
    void 样本不足时不做拟合() {
        assertNull(GrowthTrend.fit(daily(100, 110, 120)), "少于 6 个点应返回 null");
        assertNull(GrowthTrend.fit(null));
        assertNull(GrowthTrend.fit(new ArrayList<double[]>()));
    }

    @Test
    void 所有样本时间相同时无法拟合() {
        List<double[]> points = new ArrayList<double[]>();
        for (int i = 0; i < 8; i++) {
            points.add(new double[]{T0, 100 + i});
        }
        assertNull(GrowthTrend.fit(points), "时间跨度为 0 时应返回 null");
    }

    @Test
    void 预计触顶天数按当前斜率推算() {
        GrowthTrend trend = GrowthTrend.fit(daily(100, 110, 120, 130, 140, 150, 160));
        assertNotNull(trend);
        // 末值 160，容量 200，每天 10 → 4 天
        assertEquals(4D, trend.daysToReach(200D), 1e-6);
    }

    @Test
    void 不增长或已超限时不给出触顶天数() {
        GrowthTrend flat = GrowthTrend.fit(daily(100, 100, 100, 100, 100, 100));
        assertNotNull(flat);
        assertNull(flat.daysToReach(200D), "不增长时无从推算");

        GrowthTrend rising = GrowthTrend.fit(daily(100, 110, 120, 130, 140, 150, 160));
        assertNotNull(rising);
        assertNull(rising.daysToReach(150D), "已超过容量时不给天数");
        assertNull(rising.daysToReach(0D), "容量未知时不给天数");
    }

    @Test
    void 均值为零时增长率取零而不是除零() {
        GrowthTrend trend = GrowthTrend.fit(daily(0, 0, 0, 0, 0, 0));
        assertNotNull(trend);
        assertEquals(0D, trend.dailyPercent(), 1e-9);
    }

    @Test
    void 不等间隔采样也能正确归一化到每天() {
        List<double[]> points = new ArrayList<double[]>();
        // 半天一个点，每半天涨 5 → 每天 10
        for (int i = 0; i < 8; i++) {
            points.add(new double[]{T0 + i * DAY / 2, 100 + i * 5});
        }
        GrowthTrend trend = GrowthTrend.fit(points);
        assertNotNull(trend);
        assertEquals(10D, trend.getSlopePerDay(), 1e-6);
    }
}
