package com.shcj.cache.web.service;

import com.shcj.cache.entity.InstanceRiskMetric;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 主面板命中率的口径测试。
 *
 * <p>keyspace_hits / keyspace_misses 是自实例启动以来的累计计数器。原实现直接拿最新
 * 快照算 hits/(hits+misses)，得到的是「开机至今的平均命中率」——线上实测 24 小时只动了
 * 0.025 个百分点，看上去就是「数值始终不变」。正确口径是窗口末值减首值。</p>
 */
public class DashboardHitRateTest {

    private InstanceRiskMetric sample(long instanceId, long collectTime, long hits, long misses) {
        InstanceRiskMetric metric = new InstanceRiskMetric();
        metric.setInstanceId(instanceId);
        metric.setCollectTime(collectTime);
        metric.setKeyspaceHits(hits);
        metric.setKeyspaceMisses(misses);
        return metric;
    }

    @Test
    public void 算的是窗口增量而不是开机至今的累计值() {
        // 累计值 179185/25358 的比值是 87.6%，但窗口内实际只发生了 90 次命中 10 次穿透
        List<InstanceRiskMetric> samples = Arrays.asList(
                sample(13, 202608262140L, 179185, 25358),
                sample(13, 202608262240L, 179275, 25368));

        Double rate = DashboardOpsService.hitRateOf(samples);

        assertNotNull(rate);
        assertEquals(90.0, rate, 1e-9);
    }

    @Test
    public void 多实例按增量汇总() {
        List<InstanceRiskMetric> samples = Arrays.asList(
                sample(13, 202608262140L, 1000, 0),
                sample(13, 202608262240L, 1080, 20),
                sample(14, 202608262140L, 500, 500),
                sample(14, 202608262240L, 520, 880));

        // 命中 80+20=100，穿透 20+380=400
        assertEquals(20.0, DashboardOpsService.hitRateOf(samples), 1e-9);
    }

    @Test
    public void 窗口内没有请求时返回空而不是零() {
        List<InstanceRiskMetric> samples = Arrays.asList(
                sample(13, 202608262140L, 179185, 25358),
                sample(13, 202608262240L, 179185, 25358));

        // 0% 会被读成「全部穿透」，那是把「没有流量」说成了一个严重结论
        assertNull(DashboardOpsService.hitRateOf(samples));
    }

    @Test
    public void 实例在窗口内重启则跳过该实例() {
        // 13 重启过：末值比首值小，累计计数器归了零
        List<InstanceRiskMetric> samples = Arrays.asList(
                sample(13, 202608262140L, 179185, 25358),
                sample(13, 202608262240L, 40, 10),
                sample(14, 202608262140L, 1000, 0),
                sample(14, 202608262240L, 1090, 10));

        // 只用 14 的增量：90 命中 / 10 穿透
        assertEquals(90.0, DashboardOpsService.hitRateOf(samples), 1e-9);
    }

    @Test
    public void 全部实例都重启过则无法给出结论() {
        List<InstanceRiskMetric> samples = Arrays.asList(
                sample(13, 202608262140L, 179185, 25358),
                sample(13, 202608262240L, 40, 10));

        assertNull(DashboardOpsService.hitRateOf(samples));
    }

    @Test
    public void 只有一条采样时算不出增量() {
        List<InstanceRiskMetric> samples = new ArrayList<>();
        samples.add(sample(13, 202608262240L, 179185, 25358));

        assertNull(DashboardOpsService.hitRateOf(samples));
    }

    @Test
    public void 没有采样返回空() {
        assertNull(DashboardOpsService.hitRateOf(null));
        assertNull(DashboardOpsService.hitRateOf(new ArrayList<>()));
    }

    @Test
    public void 全命中与全穿透的边界() {
        assertEquals(100.0, DashboardOpsService.hitRateOf(Arrays.asList(
                sample(13, 202608262140L, 100, 7),
                sample(13, 202608262240L, 200, 7))), 1e-9);

        assertEquals(0.0, DashboardOpsService.hitRateOf(Arrays.asList(
                sample(13, 202608262140L, 100, 7),
                sample(13, 202608262240L, 100, 107))), 1e-9);
    }
}
