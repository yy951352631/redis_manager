package com.shcj.cache.web.service;

/**
 * 为 AI 助手组装当前平台只读上下文（应用/实例/慢查询等）。
 */
public interface AiPlatformContextService {

    /**
     * @param appId      当前页面应用 ID，可为 null
     * @param instanceId 当前页面实例 ID，可为 null
     */
    String buildContext(Long appId, Long instanceId);

    /**
     * @param diagnosticReportId 故障诊断报告 ID（Layer① 结果），可为 null
     */
    String buildContext(Long appId, Long instanceId, String diagnosticReportId);

    /**
     * @param diagnosticReportJson 客户端诊断报告 JSON 快照（Redis 未命中时兜底），可为 null
     */
    String buildContext(Long appId, Long instanceId, String diagnosticReportId, String diagnosticReportJson);
}
