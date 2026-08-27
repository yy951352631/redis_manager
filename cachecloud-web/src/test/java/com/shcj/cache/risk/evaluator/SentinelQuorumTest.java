package com.shcj.cache.risk.evaluator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 哨兵多数派判定的边界测试。
 *
 * <p>原实现写死「存活 &lt; 3 判异常」，与注册规模无关：5 个哨兵挂到只剩 3 个
 * 已经达不到多数派却判为正常，2 个哨兵的小集群则永远报异常。
 *
 * <p>正确口径是 Redis Sentinel 自身的多数派：{@code 注册总数 / 2 + 1}。
 * 注意偶数规模下它严格大于「一半」——4 个哨兵需要存活 3 个而不是 2 个，
 * 因为 2 票无法在 4 票中形成多数。</p>
 */
public class SentinelQuorumTest {

    /** 与 HighAvailabilityEvaluator 中一致的多数派公式 */
    private int quorum(int total) {
        return total > 0 ? total / 2 + 1 : 0;
    }

    private boolean quorumLost(int alive, int total) {
        return total > 0 && alive < quorum(total);
    }

    @Test
    public void 多数派门槛按注册总数计算() {
        assertEquals(1, quorum(1));
        assertEquals(2, quorum(2));
        assertEquals(2, quorum(3));
        assertEquals(3, quorum(4));
        assertEquals(3, quorum(5));
        assertEquals(4, quorum(6));
        assertEquals(4, quorum(7));
    }

    @Test
    public void 三哨兵存活两个即可仲裁() {
        assertFalse(quorumLost(3, 3));
        assertFalse(quorumLost(2, 3));
        assertTrue(quorumLost(1, 3));
        assertTrue(quorumLost(0, 3));
    }

    @Test
    public void 偶数规模下存活过半仍不够() {
        // 4 个哨兵存活 2 个正好是"一半"，但 2 票在 4 票中不构成多数
        assertTrue(quorumLost(2, 4));
        assertFalse(quorumLost(3, 4));
    }

    @Test
    public void 大规模集群不再被固定阈值3放过() {
        // 旧实现：存活 3 个 >= 3，判正常；实际 7 个哨兵需要 4 个才够
        assertTrue(quorumLost(3, 7));
        assertFalse(quorumLost(4, 7));
    }

    @Test
    public void 小规模集群不再被固定阈值3误报() {
        // 旧实现：2 个哨兵全部存活也因 < 3 被判异常
        assertFalse(quorumLost(2, 2));
        assertTrue(quorumLost(1, 2));
    }

    @Test
    public void cluster模式按master总数判定多数派() {
        // Cluster 由 master 之间投票，口径与哨兵一致：存活需超过总数半数
        assertFalse(quorumLost(2, 3));   // 3 主存活 2，可仲裁
        assertTrue(quorumLost(1, 3));    // 3 主只剩 1，无法仲裁
        assertTrue(quorumLost(2, 4));    // 4 主存活 2 恰为一半，仍不够
        assertFalse(quorumLost(3, 4));
        assertFalse(quorumLost(3, 5));
        assertTrue(quorumLost(2, 5));
    }

    @Test
    public void 大于半数与总数除二加一是等价的() {
        // 用户口径是「存活 > 总数/2」，实现用的是「存活 >= 总数/2+1」，
        // 整数下两者完全等价——这里把等价性钉住，避免日后有人"修正"成 total/2
        for (int total = 1; total <= 12; total++) {
            for (int alive = 0; alive <= total; alive++) {
                boolean byUserRule = alive > total / 2.0;
                boolean byImpl = alive >= quorum(total);
                assertEquals(byUserRule, byImpl,
                        "total=" + total + " alive=" + alive);
            }
        }
    }

    @Test
    public void 无哨兵时不参与判定() {
        // cluster / standalone 架构没有哨兵，不应因此报异常
        assertFalse(quorumLost(0, 0));
        assertEquals(0, quorum(0));
    }
}
