package com.shcj.cache.web.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 主面板告警归并的解析逻辑测试。
 *
 * <p>归并键是 (集群, 节点, 指标)，其中「指标」只能从 content 文本里解析。
 * 解析一旦退化，同一问题会因为内容里的数值变化而每条自成一组，
 * 归并失效、面板重新被刷屏——所以这里把三种已知内容形态全部钉住。
 * 样例取自真实库中的 app_alert_record。</p>
 */
public class DashboardOpsParseTest {

    private final DashboardOpsService service = new DashboardOpsService();

    @Test
    public void 阈值类告警解析出中文指标名() {
        assertEquals("实时ops",
                service.resolveMetric("实时ops(instantaneous_ops_per_sec) 当前 18，大于预设值 0"));
        assertEquals("分钟全量复制执行次数",
                service.resolveMetric("分钟全量复制执行次数(sync_full) 当前 1，大于预设值 0"));
    }

    @Test
    public void 同一指标不同数值必须归到同一组() {
        // 这正是归并的意义：600 条只差数值的记录要收敛成一行
        String a = service.resolveMetric("实时ops(instantaneous_ops_per_sec) 当前 18，大于预设值 0");
        String b = service.resolveMetric("实时ops(instantaneous_ops_per_sec) 当前 6，大于预设值 0");
        assertEquals(a, b);
    }

    @Test
    public void 状态变更类归为节点状态变更() {
        assertEquals("节点状态变更", service.resolveMetric(
                "Redis管理平台-实例(172.30.0.12:6379)-由运行中变为异常, 集群:4-mbphone-cluster01"));
        assertEquals("节点状态变更", service.resolveMetric(
                "Redis管理平台-实例(172.30.0.16:6379)-由异常恢复为运行中, 集群:4-mbphone-cluster01"));
    }

    @Test
    public void 节点状态异常单独成组() {
        assertEquals("节点状态异常", service.resolveMetric("节点状态异常，当前状态：异常，异常"));
    }

    @Test
    public void 无法识别时回落而不是拿整段内容当键() {
        // 拿整段当键会让归并失效，这里必须回落到固定值
        assertEquals("其它告警", service.resolveMetric("某种全新格式 12345"));
        assertEquals("其它告警", service.resolveMetric(""));
        assertEquals("其它告警", service.resolveMetric(null));
    }

    @Test
    public void 持续时长格式化() {
        assertEquals("0m", service.humanizeDuration(0));
        assertEquals("59m", service.humanizeDuration(59 * 60_000L));
        assertEquals("1h", service.humanizeDuration(60 * 60_000L));
        assertEquals("1h12m", service.humanizeDuration(72 * 60_000L));
        assertEquals("1d2h", service.humanizeDuration(26 * 3600_000L));
        assertEquals("0m", service.humanizeDuration(-1000));
    }
}
