package com.shcj.cache.web.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 评估窗口白名单测试。
 *
 * <p>背景：前端 WINDOW_OPTIONS 提供 24h / 72h / 168h 三档，而后端 ALLOWED_WINDOWS
 * 一度只有 {24, 168}。不在白名单里的窗口会被 normalizeWindow 静默改写成 168，
 * 于是「选了最近 3 天，评估窗口却没变」——两侧列表不同步是这个 bug 的根因，
 * 因此这里把三档全部锁住。</p>
 */
public class RiskAssessWindowTest {

    private final RiskAssessApiService service = new RiskAssessApiService();

    @Test
    public void 前端提供的三档窗口都必须被原样接受() {
        assertEquals(24, service.normalizeWindow(24), "最近 24 小时");
        assertEquals(72, service.normalizeWindow(72), "最近 3 天");
        assertEquals(168, service.normalizeWindow(168), "最近 7 天");
    }

    @Test
    public void 非法窗口回落到7天() {
        assertEquals(168, service.normalizeWindow(0));
        assertEquals(168, service.normalizeWindow(-1));
        assertEquals(168, service.normalizeWindow(48));
        assertEquals(168, service.normalizeWindow(720));
    }
}
