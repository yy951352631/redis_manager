package com.shcj.cache.risk.model;

/**
 * 维度与集群总评的等级。
 *
 * <p>后三个是"未得出结论"的状态，不参与总评——刻意与 NORMAL 区分开：
 * 数据不足时如果记成正常，会让人误以为检查过且没问题。
 */
public enum RiskLevel {

    NORMAL("正常", 0),
    ATTENTION("关注", 1),
    RISK("风险", 2),
    SEVERE("严重", 3),

    /** 历史积累不够（新表刚建、集群刚纳管） */
    INSUFFICIENT_DATA("数据不足", -1),
    /** 用户所选窗口短于该维度的最小窗口 */
    WINDOW_TOO_SHORT("窗口不足", -1),
    /** 该维度对当前集群类型不成立，如 sentinel 无分片倾斜 */
    NOT_APPLICABLE("不适用", -1);

    private final String label;
    private final int severity;

    RiskLevel(String label, int severity) {
        this.label = label;
        this.severity = severity;
    }

    public String getLabel() {
        return label;
    }

    public int getSeverity() {
        return severity;
    }

    /** 是否得出了有效结论并参与总评 */
    public boolean isConclusive() {
        return severity >= 0;
    }

    public static RiskLevel worse(RiskLevel a, RiskLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.severity >= b.severity ? a : b;
    }
}
