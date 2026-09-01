package com.shcj.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压测 key 定向与分布的测试。
 *
 * <p>定向压测的全部前提是「带上 hashtag 的 key 一定落在目标节点」。这个前提一旦不成立，
 * 压测结果不会报错，只会静默地压到别的节点上去——测出来的数字看着正常，结论却是错的。
 * 所以这里逐个 slot 校验，而不是抽查。</p>
 */
public class BenchmarkKeyPlannerTest {

    /** 模拟 3 主集群的槽位划分，与 172.30.0.11/12/13 的实际分配一致 */
    private static Set<Integer> slotRange(int from, int to) {
        Set<Integer> slots = new HashSet<>();
        for (int i = from; i <= to; i++) {
            slots.add(i);
        }
        return slots;
    }

    @Test
    public void 为每个节点都能找到落在其槽位内的标签() {
        Set<Integer>[] nodes = new Set[]{slotRange(0, 5460), slotRange(5461, 10922), slotRange(10923, 16383)};
        for (Set<Integer> slots : nodes) {
            String tag = BenchmarkKeyPlanner.findHashTag(slots);
            assertNotNull(tag, "应当能为该节点找到 hashtag");
            assertTrue(slots.contains(BenchmarkKeyPlanner.slotOf(tag)),
                    "tag=" + tag + " 落在 slot " + BenchmarkKeyPlanner.slotOf(tag) + "，不在目标节点持有的范围内");
        }
    }

    @Test
    public void 带标签的key全部落在目标节点上() {
        Set<Integer> slots = slotRange(5461, 10922);
        String tag = BenchmarkKeyPlanner.findHashTag(slots);
        BenchmarkKeyPlanner planner = new BenchmarkKeyPlanner(1024L, Collections.singletonList(tag), 10000, false);
        Random random = new Random(42);
        // 逐个校验而不是抽查：漏掉的那一个就是压错节点的那一次
        for (int i = 0; i < 5000; i++) {
            String key = planner.nextKey(random);
            int slot = BenchmarkKeyPlanner.slotOf(key);
            assertTrue(slots.contains(slot), "key=" + key + " 落到了 slot " + slot + "，不属于目标节点");
        }
    }

    @Test
    public void 多选节点时压力落在且只落在选中的节点上() {
        Set<Integer> a = slotRange(0, 5460);
        Set<Integer> c = slotRange(10923, 16383);
        List<String> tags = Arrays.asList(BenchmarkKeyPlanner.findHashTag(a), BenchmarkKeyPlanner.findHashTag(c));
        BenchmarkKeyPlanner planner = new BenchmarkKeyPlanner(7L, tags, 10000, false);
        Random random = new Random(7);
        int hitA = 0;
        int hitC = 0;
        for (int i = 0; i < 4000; i++) {
            int slot = BenchmarkKeyPlanner.slotOf(planner.nextKey(random));
            if (a.contains(slot)) hitA++;
            else if (c.contains(slot)) hitC++;
            else throw new AssertionError("key 落到了未选中的节点，slot=" + slot);
        }
        assertEquals(4000, hitA + hitC);
        // 两个节点都要压到，否则「多选」形同虚设
        assertTrue(hitA > 1000 && hitC > 1000, "压力未平摊: A=" + hitA + " C=" + hitC);
    }

    @Test
    public void 整集群压测不带标签() {
        BenchmarkKeyPlanner planner = new BenchmarkKeyPlanner(3L, Collections.<String>emptyList(), 100, false);
        String key = planner.nextKey(new Random(1));
        assertTrue(key.startsWith("cc:bench:3:"), key);
        assertTrue(!key.contains("{"), "整集群压测不应带 hashtag: " + key);
    }

    @Test
    public void 热点分布把访问集中到头部() {
        int keySpace = 10000;
        BenchmarkKeyPlanner hot = new BenchmarkKeyPlanner(1L, Collections.<String>emptyList(), keySpace, true);
        Random random = new Random(9);
        int hotHits = 0;
        int total = 50000;
        for (int i = 0; i < total; i++) {
            String key = hot.nextKey(random);
            int index = Integer.parseInt(key.substring(key.lastIndexOf(':') + 1));
            if (index < keySpace / 5) hotHits++;
        }
        double ratio = hotHits * 100.0 / total;
        // 目标是「两成 key 扛八成访问」，允许抽样波动
        assertTrue(ratio > 75 && ratio < 90, "热点占比 " + ratio + "%，未体现集中特征");
    }

    @Test
    public void 均匀分布不应集中在头部() {
        int keySpace = 10000;
        BenchmarkKeyPlanner uniform = new BenchmarkKeyPlanner(1L, Collections.<String>emptyList(), keySpace, false);
        Random random = new Random(9);
        int hotHits = 0;
        int total = 50000;
        for (int i = 0; i < total; i++) {
            String key = uniform.nextKey(random);
            int index = Integer.parseInt(key.substring(key.lastIndexOf(':') + 1));
            if (index < keySpace / 5) hotHits++;
        }
        double ratio = hotHits * 100.0 / total;
        assertTrue(ratio > 15 && ratio < 25, "均匀分布下前两成 key 应当只占约两成访问，实际 " + ratio + "%");
    }

    @Test
    public void 槽位集合为空时给不出标签() {
        assertNull(BenchmarkKeyPlanner.findHashTag(Collections.<Integer>emptySet()));
        assertNull(BenchmarkKeyPlanner.findHashTag(null));
    }

    @Test
    public void 单个槽位也能找到标签() {
        // 最极端的情况：节点只持有一个 slot，仍然要能定向
        for (int slot : new int[]{0, 1, 8192, 16383}) {
            String tag = BenchmarkKeyPlanner.findHashTag(Collections.singleton(slot));
            assertNotNull(tag, "slot " + slot + " 找不到 hashtag");
            assertEquals(slot, BenchmarkKeyPlanner.slotOf(tag));
        }
    }
}
