package com.shcj.cache.stats.app.impl;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集退避策略的边界测试。
 *
 * <p>两个方向都要守住：退避太急，一次网络抖动就在监控曲线上挖出一个洞；
 * 退避太狠，节点恢复了平台迟迟发现不了。
 */
public class CollectFailureBackoffTest {

    private static final long MINUTE = 60L * 1000L;
    private static final String NODE = "172.30.0.11:6379";

    @Test
    public void 偶发失败不退避以免造成数据空洞() {
        CollectFailureBackoff backoff = new CollectFailureBackoff();
        long now = 1_000_000L;

        backoff.onFailure(NODE, now);
        assertFalse(backoff.shouldSkip(NODE, now + 1), "第 1 次失败后下一轮仍要采集");

        backoff.onFailure(NODE, now + MINUTE);
        assertFalse(backoff.shouldSkip(NODE, now + MINUTE + 1), "第 2 次失败后下一轮仍要采集");
    }

    @Test
    public void 连续失败后按二的幂退避() {
        assertEquals(0L, CollectFailureBackoff.delayMillis(1));
        assertEquals(0L, CollectFailureBackoff.delayMillis(2));
        assertEquals(2 * MINUTE, CollectFailureBackoff.delayMillis(3));
        assertEquals(4 * MINUTE, CollectFailureBackoff.delayMillis(4));
    }

    @Test
    public void 退避有上限否则节点恢复发现不了() {
        for (int failures = 5; failures <= 100; failures++) {
            assertEquals(CollectFailureBackoff.MAX_DELAY_MINUTES * MINUTE,
                    CollectFailureBackoff.delayMillis(failures),
                    "连续失败 " + failures + " 次的退避应当被上限夹住");
        }
        // 指数大到会把 1<<n 溢出成负数的地步，也不能退化成 0
        assertEquals(CollectFailureBackoff.MAX_DELAY_MINUTES * MINUTE,
                CollectFailureBackoff.delayMillis(Integer.MAX_VALUE));
    }

    @Test
    public void 退避期内跳过到点恢复采集() {
        CollectFailureBackoff backoff = new CollectFailureBackoff();
        long now = 1_000_000L;
        for (int i = 0; i < 3; i++) {
            backoff.onFailure(NODE, now);
        }

        assertTrue(backoff.shouldSkip(NODE, now + MINUTE), "退避窗口内应当跳过");
        assertFalse(backoff.shouldSkip(NODE, now + 2 * MINUTE), "到点必须重新探测");
    }

    @Test
    public void 一次成功就清零退避() {
        CollectFailureBackoff backoff = new CollectFailureBackoff();
        long now = 1_000_000L;
        for (int i = 0; i < 6; i++) {
            backoff.onFailure(NODE, now);
        }
        assertTrue(backoff.shouldSkip(NODE, now + MINUTE));

        backoff.onSuccess(NODE);

        assertEquals(0, backoff.consecutiveFailures(NODE));
        assertFalse(backoff.shouldSkip(NODE, now + MINUTE), "恢复后必须立刻回到每轮采集");
    }

    @Test
    public void 各节点互不影响() {
        CollectFailureBackoff backoff = new CollectFailureBackoff();
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            backoff.onFailure("172.30.0.11:6379", now);
        }

        assertTrue(backoff.shouldSkip("172.30.0.11:6379", now + MINUTE));
        assertFalse(backoff.shouldSkip("172.30.0.12:6379", now + MINUTE));
    }

    @Test
    public void 移出采集范围的节点不再占着退避表() {
        CollectFailureBackoff backoff = new CollectFailureBackoff();
        long now = 1_000_000L;
        backoff.onFailure("172.30.0.11:6379", now);
        backoff.onFailure("172.30.0.12:6379", now);
        assertEquals(2, backoff.trackedCount());

        Set<String> stillActive = new HashSet<>();
        stillActive.add("172.30.0.11:6379");
        backoff.retainOnly(stillActive);

        assertEquals(1, backoff.trackedCount());
        assertEquals(0, backoff.consecutiveFailures("172.30.0.12:6379"));
    }
}
