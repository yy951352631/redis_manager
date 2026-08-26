package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class InstanceSlowLogItemDto {
    private long id;
    private String timeStamp;
    private long executionTime;
    private String command;
    private String clientIp;
    private String clientName;
}
