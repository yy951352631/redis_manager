package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class DashboardAbnormalAppDto {
    private long appId;
    private int clusterNo;
    private String appName;
    private String typeDesc;
    private String versionName;
    private int masterNum;
    private int slaveNum;
    private boolean topologyAbnormal;
    private boolean instanceAbnormal;
    private boolean unknownStatus;
    private String unknownStatusDetail;
    private Integer instanceAbnormalCount;
    private String instanceAbnormalDetail;
    private boolean probeAbnormal;
    private String probeAbnormalDetail;
    private boolean slotAbnormal;
    private Integer coveredSlots;
    private Integer totalSlots;
    private Integer lostSlotsCount;
    private Boolean fetchOk;
    private String statusDesc;
    private String lossDetail;
}
