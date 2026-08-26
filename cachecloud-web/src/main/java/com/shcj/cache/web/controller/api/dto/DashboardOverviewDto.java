package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DashboardOverviewDto {
    private String totalRunningApps;
    private String totalMachineCount;
    private String totalRunningInstance;
    private String redisTypeCount;
    private List<ChartItemDto> redisVersions = new ArrayList<ChartItemDto>();
    private List<String> onlineClusterNames = new ArrayList<String>();
    private List<String> onlineMachineIps = new ArrayList<String>();
    /** 机器内存：已用 GB */
    private double machineMemUsedGb;
    /** 机器内存：总量 GB */
    private double machineMemTotalGb;
    private double machineMemUsedRatio;
    /** 实例内存：已用 GB */
    private double instanceMemUsedGb;
    /** 实例内存：总量 GB */
    private double instanceMemTotalGb;
    private double instanceMemUsedRatio;
}
