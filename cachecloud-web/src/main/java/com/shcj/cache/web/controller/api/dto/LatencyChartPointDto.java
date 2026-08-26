package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class LatencyChartPointDto {
    private long timestamp;
    private long count;
}
