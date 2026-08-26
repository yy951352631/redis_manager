package com.shcj.cache.web.service.impl;

import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.InstanceSlowLog;
import com.shcj.cache.entity.InstanceStats;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.instance.InstanceStatsCenter;
import com.shcj.cache.web.service.AiPlatformContextService;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.service.FaultDiagnosticService;
import com.shcj.cache.web.vo.FaultDiagnosticReportVO;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service("aiPlatformContextService")
public class AiPlatformContextServiceImpl implements AiPlatformContextService {

    private static final int SLOW_LOG_SAMPLE_LIMIT = 5;

    @Autowired
    private AppService appService;
    @Autowired
    private InstanceStatsCenter instanceStatsCenter;
    @Autowired
    private AppStatsCenter appStatsCenter;
    @Autowired
    private FaultDiagnosticService faultDiagnosticService;

    @Override
    public String buildContext(Long appId, Long instanceId) {
        return buildContext(appId, instanceId, null, null);
    }

    @Override
    public String buildContext(Long appId, Long instanceId, String diagnosticReportId) {
        return buildContext(appId, instanceId, diagnosticReportId, null);
    }

    @Override
    public String buildContext(Long appId, Long instanceId, String diagnosticReportId, String diagnosticReportJson) {
        if ((appId == null || appId <= 0) && (instanceId == null || instanceId <= 0)
                && StringUtils.isBlank(diagnosticReportId) && StringUtils.isBlank(diagnosticReportJson)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【平台实时数据（只读，与节点列表页同源）】\n");

        if (StringUtils.isNotBlank(diagnosticReportId) || StringUtils.isNotBlank(diagnosticReportJson)) {
            appendDiagnosticReportBlock(sb, diagnosticReportId, diagnosticReportJson);
        }

        Long resolvedAppId = (appId != null && appId > 0) ? appId : null;
        if (instanceId != null && instanceId > 0) {
            InstanceInfo info = instanceStatsCenter.getInstanceInfo(instanceId);
            if (info != null && info.getAppId() > 0) {
                resolvedAppId = info.getAppId();
            }
        }

        List<InstanceInfo> instances = null;
        Map<Integer, InstanceInfo> instanceById = new HashMap<Integer, InstanceInfo>();
        if (resolvedAppId != null) {
            instances = appService.getAppInstanceInfo(resolvedAppId);
            if (instances != null) {
                for (InstanceInfo inst : instances) {
                    instanceById.put(inst.getId(), inst);
                }
            }
        }

        if (instanceId != null && instanceId > 0) {
            appendInstanceBlock(sb, instanceId, instanceById.get(instanceId.intValue()));
        }

        if (resolvedAppId != null) {
            appendAppBlock(sb, resolvedAppId, instances);
            appendSlowLogBlock(sb, resolvedAppId);
        }

        return sb.toString().trim();
    }

    private void appendDiagnosticReportBlock(StringBuilder sb, String reportId, String reportJson) {
        try {
            FaultDiagnosticReportVO report = faultDiagnosticService.resolveReport(reportId, reportJson);
            if (report == null) {
                sb.append("- 故障诊断报告 reportId=").append(reportId).append("：未找到或已过期\n");
                return;
            }
            sb.append("【故障诊断报告（Layer① 规则检查结果）】\n");
            sb.append("- reportId=").append(report.getReportId())
                    .append(" 场景=").append(report.getScenarioLabel())
                    .append(" PASS=").append(report.getPassCount())
                    .append(" WARN=").append(report.getWarnCount())
                    .append(" FAIL=").append(report.getFailCount())
                    .append(" SKIP=").append(report.getSkipCount()).append('\n');
            if (report.getChecks() != null) {
                for (com.shcj.cache.web.vo.FaultDiagnosticCheckVO check : report.getChecks()) {
                    if (check == null || check.isSkipped()) {
                        continue;
                    }
                    sb.append("  · [").append(check.getLevel()).append("] ")
                            .append(check.getCode()).append(' ').append(check.getName())
                            .append(" — ").append(check.getSummary()).append('\n');
                }
            }
            if (StringUtils.isNotBlank(report.getAiSummary())) {
                sb.append("【已生成 AI 根因结论（Layer②）】\n");
                sb.append(abbreviate(report.getAiSummary(), 2000)).append('\n');
            }
        } catch (Exception e) {
            sb.append("- 故障诊断报告读取失败\n");
        }
    }

    private void appendAppBlock(StringBuilder sb, long appId, List<InstanceInfo> instances) {
        AppDesc app = appService.getByAppId(appId);
        if (app == null) {
            sb.append("- 应用 appId=").append(appId).append("：未找到\n");
            return;
        }
        sb.append("- 应用 appId=").append(appId)
                .append(" 名称=").append(app.getName())
                .append(" 类型=").append(app.getTypeDesc())
                .append(" 状态=").append(app.getStatusDesc()).append('\n');

        if (instances != null && !instances.isEmpty()) {
            sb.append("- 节点列表（").append(instances.size()).append(" 个）：\n");
            int limit = Math.min(instances.size(), 8);
            for (int i = 0; i < limit; i++) {
                InstanceInfo inst = instances.get(i);
                sb.append("  · ").append(inst.getIp()).append(':').append(inst.getPort())
                        .append(" id=").append(inst.getId())
                        .append(" 角色=").append(formatRole(inst))
                        .append('\n');
            }
            if (instances.size() > limit) {
                sb.append("  · ... 其余 ").append(instances.size() - limit).append(" 个节点省略\n");
            }
        }
    }

    private void appendInstanceBlock(StringBuilder sb, long instanceId, InstanceInfo enriched) {
        InstanceInfo info = enriched != null ? enriched : instanceStatsCenter.getInstanceInfo(instanceId);
        if (info == null) {
            sb.append("- 实例 instanceId=").append(instanceId).append("：未找到\n");
            return;
        }
        InstanceStats stats = instanceStatsCenter.getInstanceStats(instanceId);
        sb.append("- 实例 instanceId=").append(instanceId)
                .append(' ').append(info.getIp()).append(':').append(info.getPort())
                .append(" appId=").append(info.getAppId())
                .append(" 类型=").append(info.getTypeDesc())
                .append(" 角色=").append(formatRole(info))
                .append(" 状态=").append(info.getStatusDesc()).append('\n');
        if (stats != null) {
            sb.append("  · 内存 ").append(formatMb(stats.getUsedMemory())).append('/')
                    .append(formatMb(stats.getEffectiveMaxMemory()))
                    .append(" (").append(stats.getMemUsePercent()).append("%)")
                    .append(" 连接=").append(stats.getCurrConnections())
                    .append(" key数=").append(stats.getCurrItems())
                    .append(" 命中率=").append(stats.getHitPercent())
                    .append(" 在线=").append(stats.isRun() ? "是" : "否").append('\n');
        } else {
            sb.append("  · 暂无 instance_statistics 采集数据\n");
        }
    }

    private void appendSlowLogBlock(StringBuilder sb, long appId) {
        try {
            Date end = new Date();
            Date start = DateUtils.addDays(end, -1);
            Map<String, Long> countMap = appStatsCenter.getInstanceSlowLogCountMapByAppId(appId, start, end);
            long total = 0;
            if (countMap != null) {
                for (Long c : countMap.values()) {
                    if (c != null) {
                        total += c;
                    }
                }
            }
            sb.append("- 近24小时慢查询：共 ").append(total).append(" 条\n");
            if (total > 0) {
                List<InstanceSlowLog> logs = appStatsCenter.getInstanceSlowLogByAppId(appId, start, end);
                if (logs != null && !logs.isEmpty()) {
                    sb.append("- 慢查询样例（最多 ").append(SLOW_LOG_SAMPLE_LIMIT).append(" 条）：\n");
                    int limit = Math.min(logs.size(), SLOW_LOG_SAMPLE_LIMIT);
                    for (int i = 0; i < limit; i++) {
                        InstanceSlowLog log = logs.get(i);
                        sb.append("  · ").append(log.getIp()).append(':').append(log.getPort())
                                .append(" 耗时=").append(log.getCostTime()).append("us")
                                .append(" 命令=").append(abbreviate(log.getCommand(), 120)).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            sb.append("- 慢查询数据读取失败\n");
        }
    }

    private String formatMb(long bytes) {
        if (bytes <= 0) {
            return "0MB";
        }
        return String.format("%.2fMB", bytes / 1024.0 / 1024.0);
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }

    private String formatRole(InstanceInfo inst) {
        return StringUtils.defaultString(inst != null ? inst.getRoleDesc() : null, "未知");
    }
}
