package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class DashboardChartsDto {
    private List<ChartItemDto> appTypeDistribute;
    private List<ChartItemDto> appMemUsageDistribute;
    private List<ChartItemDto> appShardDistribute;
    private List<ChartItemDto> instanceStatusDistribute;
}
