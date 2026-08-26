package com.shcj.cache.datamodel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationTest {

    private List<double[]> points(double[] xs, double[] ys) {
        List<double[]> list = new ArrayList<double[]>();
        for (int i = 0; i < xs.length; i++) {
            list.add(new double[]{xs[i], ys[i]});
        }
        return list;
    }

    /** y = 2x + 1 上的 n 个点 */
    private List<double[]> perfectLine(int n, double slope, double intercept) {
        List<double[]> list = new ArrayList<double[]>();
        for (int i = 0; i < n; i++) {
            list.add(new double[]{i, slope * i + intercept});
        }
        return list;
    }

    @Test
    void 完全正相关的系数为1且斜率正确() {
        Correlation c = Correlation.of(perfectLine(20, 2D, 1D));
        assertNotNull(c);
        assertEquals(1D, c.getCoefficient(), 1e-9);
        assertEquals(2D, c.getSlope(), 1e-9);
        assertEquals(1D, c.getIntercept(), 1e-9);
        assertEquals("STRONG", c.strength());
    }

    @Test
    void 完全负相关的系数为负1() {
        Correlation c = Correlation.of(perfectLine(20, -3D, 100D));
        assertNotNull(c);
        assertEquals(-1D, c.getCoefficient(), 1e-9);
        assertEquals(-3D, c.getSlope(), 1e-9);
        assertEquals("STRONG", c.strength(), "强相关看绝对值，负相关同样是强相关");
    }

    @Test
    void 无关数据的系数接近零且判为无相关() {
        // x 递增，y 在两个值之间来回摆，协方差相互抵消
        double[] xs = new double[20];
        double[] ys = new double[20];
        for (int i = 0; i < 20; i++) {
            xs[i] = i;
            ys[i] = i % 2 == 0 ? 10 : 20;
        }
        Correlation c = Correlation.of(points(xs, ys));
        assertNotNull(c);
        assertTrue(Math.abs(c.getCoefficient()) < Correlation.WEAK_THRESHOLD,
                "实测 r=" + c.getCoefficient());
        assertEquals("NONE", c.strength());
    }

    @Test
    void y全部相同时判为无相关而不是抛异常() {
        double[] xs = {1, 2, 3, 4, 5, 6};
        double[] ys = {7, 7, 7, 7, 7, 7};
        Correlation c = Correlation.of(points(xs, ys));
        assertNotNull(c);
        assertEquals(0D, c.getCoefficient(), 1e-9, "分母为 0 时按无相关处理");
        assertEquals(0D, c.getSlope(), 1e-9);
        assertEquals("NONE", c.strength());
    }

    @Test
    void x全部相同时无法拟合() {
        double[] xs = {5, 5, 5, 5, 5, 5};
        double[] ys = {1, 2, 3, 4, 5, 6};
        assertNull(Correlation.of(points(xs, ys)), "x 无变化时斜率无定义");
    }

    @Test
    void 样本过少时不做分析() {
        assertNull(Correlation.of(perfectLine(1, 1D, 0D)));
        assertNull(Correlation.of(null));
        assertNull(Correlation.of(new ArrayList<double[]>()));
    }

    @Test
    void 样本量决定相关系数是否可信() {
        Correlation few = Correlation.of(perfectLine(10, 2D, 0D));
        Correlation enough = Correlation.of(perfectLine(15, 2D, 0D));
        assertNotNull(few);
        assertNotNull(enough);
        // 两者都是完美相关，但少样本下的 r 不该被展示——纯随机数据在 n=5 时也常算出高相关
        assertFalse(few.isReliable(), "少于 15 个点不给系数");
        assertTrue(enough.isReliable());
    }

    @Test
    void 中等相关落在两个阈值之间() {
        // 线性趋势叠加噪声，构造一个 |r| 在 0.3~0.7 之间的样本
        double[] xs = new double[20];
        double[] ys = new double[20];
        double[] noise = {6, -5, 4, -6, 5, -4, 6, -5, 4, -6, 5, -4, 6, -5, 4, -6, 5, -4, 6, -5};
        for (int i = 0; i < 20; i++) {
            xs[i] = i;
            ys[i] = i * 0.6 + noise[i];
        }
        Correlation c = Correlation.of(points(xs, ys));
        assertNotNull(c);
        double abs = Math.abs(c.getCoefficient());
        assertTrue(abs >= Correlation.WEAK_THRESHOLD && abs < Correlation.STRONG_THRESHOLD,
                "期望中等相关，实测 r=" + c.getCoefficient());
        assertEquals("MODERATE", c.strength());
    }

    @Test
    void 拟合线端点落在数据的x范围两端() {
        Correlation c = Correlation.of(perfectLine(20, 2D, 1D));
        assertNotNull(c);
        double[][] line = c.linePoints();
        assertEquals(0D, line[0][0], 1e-9, "起点是最小 x");
        assertEquals(19D, line[1][0], 1e-9, "终点是最大 x");
        assertEquals(1D, line[0][1], 1e-9, "y = 2*0 + 1");
        assertEquals(39D, line[1][1], 1e-9, "y = 2*19 + 1");
    }

    @Test
    void 相关系数与数值量级无关() {
        // 把 y 放大一百万倍，r 不变——相关系数是标准化的
        Correlation small = Correlation.of(perfectLine(20, 2D, 1D));
        List<double[]> scaled = new ArrayList<double[]>();
        for (double[] p : perfectLine(20, 2D, 1D)) {
            scaled.add(new double[]{p[0], p[1] * 1_000_000});
        }
        Correlation large = Correlation.of(scaled);
        assertNotNull(small);
        assertNotNull(large);
        assertEquals(small.getCoefficient(), large.getCoefficient(), 1e-9);
    }
}
