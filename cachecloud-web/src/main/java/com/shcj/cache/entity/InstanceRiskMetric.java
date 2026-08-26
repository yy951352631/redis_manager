package com.shcj.cache.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * 风险评估用的实例分钟级指标快照。
 *
 * <p>采集链路每分钟拿到 INFO 后顺带落库，用于替代只保留 10 分钟的 {@code standard_statistics}——
 * 后者存的是完整 INFO 的 JSON，写放大大、留存短，撑不起趋势与倾斜判定。
 */
@Data
public class InstanceRiskMetric implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** yyyyMMddHHmm */
    private long collectTime;

    private long appId;
    private long instanceId;
    private String ip;
    private int port;

    /** 1 master，2 slave */
    private int role;

    // ---- 内存 ----
    private long usedMemory;
    private long maxMemory;
    /** INFO Memory.total_system_memory，maxmemory 未设置时作为使用率的分母 */
    private long totalSystemMemory;
    private double memFragRatio;

    // ---- keyspace ----
    private long keysCount;
    private long expiresCount;
    private long avgTtl;

    // ---- 连接 ----
    private int connectedClients;
    /** 累计值，增长率需自行做差分 */
    private long rejectedConnections;

    // ---- 命中 ----
    private long keyspaceHits;
    private long keyspaceMisses;
    private long expiredKeys;
    private long evictedKeys;

    // ---- 持久化 ----
    private long latestForkUsec;
    private int rdbLastBgsaveTimeSec;
    private long aofDelayedFsync;

    // ---- 吞吐 ----
    private long instantaneousOps;
    private long netInputBytes;
    private long netOutputBytes;

    // ---- CPU（累计秒数，增长率需差分）----
    private double usedCpuSys;
    private double usedCpuUser;

    /** 集群纪元，非 cluster 实例为 0 */
    private long clusterMyEpoch;
}
