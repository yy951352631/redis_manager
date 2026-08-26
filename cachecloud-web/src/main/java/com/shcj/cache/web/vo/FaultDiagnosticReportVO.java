package com.shcj.cache.web.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 一次故障诊断完整报告（Layer① 规则检查结果）。
 */
public class FaultDiagnosticReportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String reportId;
    private long appId;
    private String appName;
    private String appTypeDesc;
    private String scenario;
    private String scenarioLabel;
    private boolean externalManaged;
    private boolean sshCapable;
    private long durationMs;
    private Date createTime;
    private int passCount;
    private int warnCount;
    private int failCount;
    private int skipCount;
    private int totalCount;
    private List<FaultDiagnosticCheckVO> checks = new ArrayList<FaultDiagnosticCheckVO>();
    private String aiSummary;

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public long getAppId() {
        return appId;
    }

    public void setAppId(long appId) {
        this.appId = appId;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getAppTypeDesc() {
        return appTypeDesc;
    }

    public void setAppTypeDesc(String appTypeDesc) {
        this.appTypeDesc = appTypeDesc;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public String getScenarioLabel() {
        return scenarioLabel;
    }

    public void setScenarioLabel(String scenarioLabel) {
        this.scenarioLabel = scenarioLabel;
    }

    public boolean isExternalManaged() {
        return externalManaged;
    }

    public void setExternalManaged(boolean externalManaged) {
        this.externalManaged = externalManaged;
    }

    public boolean isSshCapable() {
        return sshCapable;
    }

    public void setSshCapable(boolean sshCapable) {
        this.sshCapable = sshCapable;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public int getPassCount() {
        return passCount;
    }

    public void setPassCount(int passCount) {
        this.passCount = passCount;
    }

    public int getWarnCount() {
        return warnCount;
    }

    public void setWarnCount(int warnCount) {
        this.warnCount = warnCount;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public int getSkipCount() {
        return skipCount;
    }

    public void setSkipCount(int skipCount) {
        this.skipCount = skipCount;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public List<FaultDiagnosticCheckVO> getChecks() {
        return checks;
    }

    public void setChecks(List<FaultDiagnosticCheckVO> checks) {
        this.checks = checks;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }
}
