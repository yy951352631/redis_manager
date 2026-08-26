package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class LatencyInstanceCountDto {
    private String hostPort;
    private long count;
    private long instanceId;
}
