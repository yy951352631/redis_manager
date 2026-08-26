package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

/** 大盘详情（较重，可异步加载） */
@Data
public class DashboardDetailsDto {
    private DashboardAbnormalOverviewDto abnormalOverview;
    private DashboardChartsDto charts;
}
