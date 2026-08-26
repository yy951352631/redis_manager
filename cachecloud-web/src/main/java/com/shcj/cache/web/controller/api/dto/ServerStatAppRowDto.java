package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ServerStatAppRowDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private long appId;
    private int clusterNo;
    private String appName;
    private boolean testApp;
    private String testLabel;
    private String typeDesc;
    private String versionName;

    private int masterNum;
    private int slaveNum;
    private boolean nodeBalanced;
    private double shardMemGb;
    private String shardSpec;

    private double memTotalGb;
    private double memUsedGb;
    private double memUsedRssGb;
    private double memUsageRatio;
    private double memUsageRatioRss;
    private double memUsePercent;

    private long slowLogCount;
    private long connectedClients;

    private long objectSize;
    private double usedMemoryMb;
    private double usedMemoryRssMb;
    private double avgMemFragRatio;
    private double maxCpuSys;
    private double maxCpuUser;

    private Integer topologyExamResult;
    private String topologyExamLabel;
}
