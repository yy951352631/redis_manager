package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 集群管理列表行（M3）
 */
@Data
public class AppListItemDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private long appId;
    private int clusterNo;
    private String appName;
    private String typeDesc;
    private boolean externalManaged;
    private String sourceLabel;
    private List<String> nodeLines = new ArrayList<>();
    private int extraNodeCount;
    /** 列表未展示的其余节点（用于悬停提示） */
    private List<String> extraNodeLines = new ArrayList<>();
    /** 全部节点（悬停展示） */
    private List<String> allNodeLines = new ArrayList<>();
    private int instanceCount;
    private List<String> matchedNodes = new ArrayList<>();
    private int runtimeStatus;
    private String runtimeStatusLabel;
    /** 集群异常原因，例如探活失败的数据节点地址。 */
    private String runtimeStatusDetail;
    private boolean hasOfflineInstances;

    private String versionName;
    /** 内存总量 MB */
    private long mem;
    private double memUsePercent;
    private double highestMemFragRatio;
    private long instIdWithHighestMemFragRatio;
    private double hitPercent;
    /** 主节点 key 总数；INFO keyspace 已按全部 DB 汇总。 */
    private long keyCount;
    /** 最近两次采集计算出的集群 CPU 使用率（百分比）。 */
    private double cpuUsePercent;
    /** 实例运行时长，单位秒。 */
    private long uptimeSeconds;
    private int appRunDays;
}
