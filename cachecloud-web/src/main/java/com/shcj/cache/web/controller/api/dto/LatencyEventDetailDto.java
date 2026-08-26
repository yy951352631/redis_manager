package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class LatencyEventDetailDto {
    private long id;
    private long instanceId;
    private String hostPort;
    private String event;
    private String executeTime;
    private long executionCost;
}
