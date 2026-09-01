package com.shcj.cache.benchmark;

import redis.clients.jedis.util.JedisClusterCRC16;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 压测 key 的生成规则。
 *
 * <p>两件事：让 key 落到指定节点，以及按指定分布挑选 key。
 *
 * <h3>定向到节点</h3>
 * Redis Cluster 只对 <code>{}</code> 之间的内容算 CRC16，所以只要找到一个 hashtag，
 * 使 <code>CRC16(tag) % 16384</code> 落在目标节点持有的 slot 里，带上这个 tag 的 key
 * 就必定路由到该节点。tag 是暴力枚举出来的：16384 个槽位下，随便试几十个短字符串
 * 就能命中，一次性算好缓存起来即可。
 *
 * <h3>key 分布</h3>
 * 均匀随机测的是"数据全在内存里"的理想情况；真实业务几乎都是少数 key 扛住大部分
 * 访问，热点模式用 Zipf 近似这一点。两者测出来的命中率与延迟分布差别很大。
 */
public class BenchmarkKeyPlanner {

    /** 键名前缀，压测产生的数据一律带上，便于识别与清理 */
    public static final String KEY_PREFIX = "cc:bench:";

    /** 枚举 hashtag 的最大尝试次数，正常几十次就能命中一个槽位 */
    private static final int MAX_TAG_ATTEMPTS = 200000;

    private static final int SLOT_COUNT = 16384;

    private final long taskId;

    /** 定向压测时每个目标节点对应的 hashtag；整集群压测时为空 */
    private final List<String> hashTags;

    private final int keySpace;

    private final boolean hotspot;

    /**
     * @param taskId   任务号，进入 key 前缀，保证不同任务互不干扰
     * @param hashTags 目标节点的 hashtag 列表，整集群压测传空集合
     * @param keySpace 键空间大小
     * @param hotspot  true = 热点分布（Zipf 近似），false = 均匀随机
     */
    public BenchmarkKeyPlanner(long taskId, Collection<String> hashTags, int keySpace, boolean hotspot) {
        this.taskId = taskId;
        this.hashTags = hashTags == null || hashTags.isEmpty()
                ? Collections.<String>emptyList() : new ArrayList<>(hashTags);
        this.keySpace = Math.max(1, keySpace);
        this.hotspot = hotspot;
    }

    /**
     * 按分布挑一个序号并拼出 key。
     *
     * @param random 调用方自带的随机源，每个压测线程一个，避免共享 Random 成为竞争点
     */
    public String nextKey(Random random) {
        int index = hotspot ? zipfIndex(random) : random.nextInt(keySpace);
        return keyAt(index, random);
    }

    /** 指定序号的 key，清理时按同一规则重放出全部键名 */
    public String keyAt(int index, Random random) {
        if (hashTags.isEmpty()) {
            return KEY_PREFIX + taskId + ':' + index;
        }
        // 多选节点时轮流落到各个 tag 上，让压力平摊到选中的节点
        String tag = hashTags.get(hashTags.isEmpty() ? 0
                : (random == null ? index % hashTags.size() : random.nextInt(hashTags.size())));
        return KEY_PREFIX + taskId + ":{" + tag + "}:" + index;
    }

    public int getKeySpace() {
        return keySpace;
    }

    public List<String> getHashTags() {
        return Collections.unmodifiableList(hashTags);
    }

    /**
     * Zipf 近似：约 20% 的 key 承担约 80% 的访问。
     *
     * <p>没有用严格的 Zipf 采样（要预先算归一化常数、开销大且每次请求都要二分），
     * 压测只需要"访问集中在头部"这个性质，两段式抽样足够且是 O(1)。</p>
     */
    private int zipfIndex(Random random) {
        int hotSize = Math.max(1, keySpace / 5);
        return random.nextInt(100) < 80 ? random.nextInt(hotSize) : random.nextInt(keySpace);
    }

    /**
     * 为一组 slot 找出一个 hashtag，使其 CRC16 落在其中。
     *
     * @return 找到的 hashtag；给定 slot 集合为空时返回 null
     */
    public static String findHashTag(Collection<Integer> slots) {
        if (slots == null || slots.isEmpty()) {
            return null;
        }
        for (int i = 0; i < MAX_TAG_ATTEMPTS; i++) {
            String candidate = Integer.toString(i, 36);
            int slot = JedisClusterCRC16.getSlot(candidate) % SLOT_COUNT;
            if (slots.contains(slot)) {
                return candidate;
            }
        }
        return null;
    }

    /** 校验用：算出 key 实际会落到哪个 slot */
    public static int slotOf(String key) {
        return JedisClusterCRC16.getSlot(key);
    }
}
