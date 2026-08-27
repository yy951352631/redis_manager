package com.shcj.cache.stats.app.impl;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 采集失败节点的退避表。
 *
 * <p>采集是每分钟一轮、按实例逐个执行的。一个连不上的节点每轮都要把 4 个采集点
 * 各自的连接超时和重试跑满（约 8 秒），死节点一多，一轮就跑不完 60 秒，
 * {@code scheduledCollecting} 互斥会直接跳过下一轮——结果是所有实例一起丢数据，
 * 偏偏是在最需要监控数据的时候。
 *
 * <p>策略：前 {@link #TOLERATED_FAILURES} 次失败不退避，避免一次网络抖动就造成数据空洞；
 * 之后按 2 的幂退避，上限 {@link #MAX_DELAY_MINUTES} 分钟。上限存在的理由是恢复探测——
 * 节点活过来的发现延迟不能超过这个值，所以不能无限翻倍。
 */
public class CollectFailureBackoff {

    /** 这么多次以内的连续失败照常每轮采集 */
    static final int TOLERATED_FAILURES = 2;

    /** 退避上限，同时也是节点恢复被发现的最大延迟 */
    static final int MAX_DELAY_MINUTES = 5;

    private static final long MINUTE_MILLIS = 60L * 1000L;

    private final Map<String, State> states = new ConcurrentHashMap<String, State>();

    private static final class State {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long nextAttemptAt;
    }

    /**
     * 计算第 {@code failures} 次连续失败之后要等多久再试，单位毫秒。
     */
    static long delayMillis(int failures) {
        if (failures <= TOLERATED_FAILURES) {
            return 0L;
        }
        int exponent = failures - TOLERATED_FAILURES;
        // 1<<30 就已经远超上限，先夹住指数免得溢出成负数
        int minutes = exponent >= 30 ? MAX_DELAY_MINUTES : Math.min(1 << exponent, MAX_DELAY_MINUTES);
        return minutes * MINUTE_MILLIS;
    }

    public boolean shouldSkip(String key, long now) {
        State state = states.get(key);
        return state != null && now < state.nextAttemptAt;
    }

    public void onSuccess(String key) {
        states.remove(key);
    }

    public void onFailure(String key, long now) {
        State state = states.get(key);
        if (state == null) {
            State created = new State();
            State existing = states.putIfAbsent(key, created);
            state = existing == null ? created : existing;
        }
        int failures = state.failures.incrementAndGet();
        state.nextAttemptAt = now + delayMillis(failures);
    }

    public int consecutiveFailures(String key) {
        State state = states.get(key);
        return state == null ? 0 : state.failures.get();
    }

    /** 已不在采集范围内的节点（下线、删除）不该继续占着退避表 */
    public void retainOnly(Set<String> activeKeys) {
        states.keySet().retainAll(activeKeys);
    }

    public int trackedCount() {
        return states.size();
    }
}
