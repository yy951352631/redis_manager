package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.List;

/** 大盘首屏（轻量，优先返回） */
@Data
public class DashboardOverviewBundleDto {
    private DashboardOverviewDto overview;
    private List<DashboardMachineStatsDto> machineStats;
    private DashboardQuartzStatsDto quartz;
    private DashboardTaskStatsDto tasks;
}
