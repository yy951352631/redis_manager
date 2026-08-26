package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class DashboardTaskStatsDto {
    private int totalTaskCount;
    private int newTaskCount;
    private int runningTaskCount;
    private int abortTaskCount;
    private int successTaskCount;
}
