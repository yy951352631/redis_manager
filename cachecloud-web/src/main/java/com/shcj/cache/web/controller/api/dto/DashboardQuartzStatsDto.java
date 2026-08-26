package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class DashboardQuartzStatsDto {
    private int triggerTotalCount;
    private int triggerWaitingCount;
    private int triggerErrorCount;
    private int triggerPausedCount;
    private int triggerAcquiredCount;
    private int triggerBlockedCount;
    private int misfireCount;
}
