package com.shcj.cache.web.service;

import com.shcj.cache.web.vo.FaultDiagnosticReportVO;

import java.util.List;

/**
 * 故障诊断（Layer① 规则检查）。
 */
public interface FaultDiagnosticService {

    String SCENARIO_REPLICATION_BREAK = "replication_break";

    /**
     * 执行诊断并持久化报告。
     */
    FaultDiagnosticReportVO run(long appId, String scenario);

    FaultDiagnosticReportVO getReport(String reportId);

    List<FaultDiagnosticReportVO> listHistory(long appId, int limit);

    /**
     * Layer②：基于报告生成 AI 根因分析（结果写回报告缓存）。
     */
    String generateAiSummary(String reportId);

    /**
     * 按 reportId 查找；Assist Redis 未命中时可从客户端 JSON 快照恢复。
     */
    FaultDiagnosticReportVO resolveReport(String reportId, String reportJson);

    /**
     * Layer②：直接基于报告对象生成 AI 摘要（用于 Redis 未持久化时的兜底）。
     */
    String generateAiSummaryForReport(FaultDiagnosticReportVO report);

    /**
     * 集群节点 IP 是否均已关联 machine_info 且配置了 SSH（可做主机/网络层诊断）。
     */
    boolean isSshCapableForApp(long appId);

    /**
     * 集群是否全部为外部纳管节点（host_id=0）。
     */
    boolean isExternalManagedForApp(long appId);
}
