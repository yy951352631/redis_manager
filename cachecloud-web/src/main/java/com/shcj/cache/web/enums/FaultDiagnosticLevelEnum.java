package com.shcj.cache.web.enums;

/**
 * 故障诊断检查项级别（规则引擎输出，非 AI 判定）。
 */
public enum FaultDiagnosticLevelEnum {

    PASS("PASS", "通过"),
    WARN("WARN", "警告"),
    FAIL("FAIL", "失败"),
    SKIP("SKIP", "跳过");

    private final String code;
    private final String label;

    FaultDiagnosticLevelEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
