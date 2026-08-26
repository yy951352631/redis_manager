package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class InstanceStatDto {
    private long instanceId;
    private long appId;
    private String appName;
    private String hostPort;
    private int instanceType;
    private String typeDesc;
    private String statusDesc;
    private String roleDesc;
    private double memUsePercent;
    private double memUsedGb;
    private double memTotalGb;
    private String hitPercent;
    private long currItems;
    private int currConnections;
    private double memFragmentationRatio;
    /** Redis 运行时长（秒），取自 INFO server 的 uptime_in_seconds */
    private long uptimeSeconds;
    /** 操作系统架构：X86 / ARM，解析自 INFO server 的 os 字段 */
    private String osArch;
    /** INFO server 的 os 原文，作为架构判定的依据展示在提示中 */
    private String osInfo;
    private boolean realtimeSupported;
    private String startDate;
    private String endDate;
    private Map<String, Map<String, String>> infoSections = new LinkedHashMap<>();
    private List<ChartPointDto> topCommands = new ArrayList<>();
    private List<AppCommandClimaxDto> top5Climax = new ArrayList<>();
}
