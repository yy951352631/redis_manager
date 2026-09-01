package com.shcj.cache.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快捷压测停止判据的测试。
 *
 * <p>判据错在两个方向都很糟：停早了会把远未打满的集群报成峰值，得出偏低的容量结论；
 * 停晚了则一路把并发加到几百，延迟劣化、错误飙升，测出来的也不是可用容量。</p>
 */
public class BenchmarkRampPlanTest {

    @Test
    public void cpu打满时停止() {
        String reason = BenchmarkRampPlan.stopReason(92.0, 100000, 50000, 0, 100000);
        assertNotNull(reason);
        assertTrue(reason.contains("CPU"), reason);
    }

    @Test
    public void cpu未打满且吞吐仍在涨时继续() {
        // 并发翻倍带来 80% 提升，显然还有余量
        assertNull(BenchmarkRampPlan.stopReason(45.0, 90000, 50000, 0, 90000));
    }

    @Test
    public void 吞吐到拐点时停止() {
        // 并发翻倍只换来 2% 提升
        String reason = BenchmarkRampPlan.stopReason(60.0, 102000, 100000, 0, 102000);
        assertNotNull(reason);
        assertTrue(reason.contains("拐点"), reason);
    }

    @Test
    public void 吞吐不升反降也算拐点() {
        String reason = BenchmarkRampPlan.stopReason(60.0, 88000, 100000, 0, 88000);
        assertNotNull(reason);
        assertTrue(reason.contains("拐点"), reason);
    }

    @Test
    public void 错误率过高时停止() {
        String reason = BenchmarkRampPlan.stopReason(50.0, 100000, 50000, 2000, 100000);
        assertNotNull(reason);
        assertTrue(reason.contains("错误率"), reason);
    }

    @Test
    public void 少量错误不触发停止() {
        // 0.5% 错误率在阈值内，不该因为偶发超时就中断整轮爬坡
        assertNull(BenchmarkRampPlan.stopReason(50.0, 100000, 50000, 500, 100000));
    }

    @Test
    public void 首档没有上一档可比时只看cpu与错误() {
        assertNull(BenchmarkRampPlan.stopReason(50.0, 100000, 0, 0, 100000));
        assertNotNull(BenchmarkRampPlan.stopReason(95.0, 100000, 0, 0, 100000));
    }

    @Test
    public void 判据优先级cpu优先于拐点() {
        // 两个条件同时成立时应报 CPU —— 「打满了」比「涨不动了」更能说明原因
        String reason = BenchmarkRampPlan.stopReason(95.0, 101000, 100000, 0, 101000);
        assertTrue(reason.contains("CPU"), reason);
    }

    @Test
    public void 档位倍增覆盖两个数量级() {
        int[] steps = BenchmarkRampPlan.CONCURRENCY_STEPS;
        assertEquals(1, steps[0], "起点必须够低，否则默认 Pipeline 下一档就触顶");
        assertEquals(512, steps[steps.length - 1]);
        for (int i = 1; i < steps.length; i++) {
            assertEquals(steps[i - 1] * 2, steps[i], "档位应当倍增");
        }
    }

    @Test
    public void 没有请求时不做除零() {
        assertNull(BenchmarkRampPlan.stopReason(10.0, 0, 0, 0, 0));
    }
}
