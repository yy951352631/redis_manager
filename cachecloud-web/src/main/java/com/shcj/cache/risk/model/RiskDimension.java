package com.shcj.cache.risk.model;

/**
 * 评估维度定义。
 *
 * <p>{@code minWindowHours} 是该维度在统计上成立所需的最小窗口——它是指标的物理属性而非用户偏好：
 * 内存增长率在 24 小时尺度上算出来的是日内波峰波谷，不是趋势。用户所选窗口小于此值时，
 * 该维度报 {@link RiskLevel#WINDOW_TOO_SHORT} 而不是给出一个可疑的数字。
 */
public enum RiskDimension {

    MEMORY_USAGE("内存使用率", 0, false),
    HIT_RATE("缓存命中率", 24, false),
    SLOW_LOG("慢日志", 24, false),
    DANGEROUS_COMMAND("危险命令调用", 24, false),
    CONNECTION_USAGE("客户端连接数", 0, false),
    REJECTED_CONNECTION("被拒绝的连接", 24, false),
    FORK_DURATION("fork 子进程耗时", 0, false),
    RDB_DURATION("RDB 写盘耗时", 0, false),
    AOF_FSYNC_DELAY("AOF 刷盘延迟", 24, false),
    KEY_EXPIRE("key 过期设置", 0, false),
    CLUSTER_SKEW("集群倾斜", 0, true),
    CLUSTER_EPOCH("集群切换纪元", 24, true),

    // ---- 第二批：依赖新表积累历史 ----
    MEMORY_GROWTH("内存增长率", 72, false),
    CONNECTION_GROWTH("连接增长率", 72, false),
    COMMAND_LATENCY("命令执行耗时", 72, false),

    // ---- 第三批：安全与可用性。读实例当前配置，与历史窗口无关，故 minWindowHours = 0 ----
    ACCESS_PASSWORD("访问密码强度", 0, false),
    HIGH_AVAILABILITY("高可用性", 0, false),
    PERSISTENCE("持久化配置", 0, false);

    private final String label;
    private final int minWindowHours;
    private final boolean clusterOnly;

    RiskDimension(String label, int minWindowHours, boolean clusterOnly) {
        this.label = label;
        this.minWindowHours = minWindowHours;
        this.clusterOnly = clusterOnly;
    }

    public String getLabel() {
        return label;
    }

    public int getMinWindowHours() {
        return minWindowHours;
    }

    /** 仅对 redis-cluster 成立（分片倾斜、纪元） */
    public boolean isClusterOnly() {
        return clusterOnly;
    }
}
