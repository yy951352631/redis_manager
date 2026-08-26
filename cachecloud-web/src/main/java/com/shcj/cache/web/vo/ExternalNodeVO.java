package com.shcj.cache.web.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 外部纳管 Redis 节点（instance_info 一行对应列表一行）。
 */
@Data
public class ExternalNodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private int instanceId;
    private String ip;
    private int port;
    /** sentinel / redis-server / redis-cluster */
    private String nodeTypeDesc;
    /** master / slave / sentinel */
    private String roleDesc;
    private long appId;
    private int clusterNo;
    private String appName;
    private String appTypeDesc;
    private int status;
    private String statusDesc;
    /** sentinel 时为 master 名；数据节点可为内存配置 */
    private String cmd;
    /** 是否已写入 instance_info */
    private boolean synced;
    /** 配额内存 MB，用于展示 Total */
    private long memMb;
    /** 内存使用率 0–100 */
    private double memUsePercent;
    /** 已用内存 GB（展示） */
    private double usedMemGb;
    /** 总内存 GB（展示） */
    private double totalMemGb;
    /** 当前实例全部 DB 的 key 数量。 */
    private long keyCount;
    /** 当前实例最近采集计算出的 CPU 使用率（百分比）。 */
    private double cpuUsePercent;
    /** Redis uptime，单位秒。 */
    private long uptimeSeconds;
    /** INFO memory 的 mem_fragmentation_ratio。 */
    private double memFragmentationRatio;
    /** INFO clients 的 connected_clients。 */
    private int connectedClients;
}
