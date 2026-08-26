package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class AppSlowLogItemDto {
    private long id;
    private long slowLogId;
    private long instanceId;
    private String ip;
    private int port;
    private String hostPort;
    private int costTime;
    private String command;
    private String executeTime;
    private String clientIp;
}
