package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TopologyExamDto {
    private long appId;
    private int clusterNo;
    private String appName;
    private int appType;
    private String typeDesc;
    private boolean overallOk;
    private int issueCount;
    private boolean clusterOrSentinel;
    private int diagPass;
    private int diagTotal;
    private int slaveNum;
    private int masterCount;
    private int sentinelCount;
    private int machineGroupCount;
    private boolean msFlag;
    private boolean sameNetSegment;
    private Boolean failoverOk;
    private List<TopologyExamDiagItemDto> diagnostics = new ArrayList<>();
    private List<TopologyExamTipDto> tips = new ArrayList<>();
    private TopologyExamSlotDto slotExam;
    private List<TopologyExamInstanceItemDto> sentinels = new ArrayList<>();
    private List<TopologyExamMasterSlaveGroupDto> masterSlaves = new ArrayList<>();
    private List<TopologyExamMachineGroupDto> machines = new ArrayList<>();
}
