package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class DashboardDto {
    private DashboardOverviewDto overview;
    private DashboardAbnormalOverviewDto abnormalOverview;
    private DashboardChartsDto charts;
    private List<DashboardMachineStatsDto> machineStats;
    private DashboardQuartzStatsDto quartz;
    private DashboardTaskStatsDto tasks;
}
