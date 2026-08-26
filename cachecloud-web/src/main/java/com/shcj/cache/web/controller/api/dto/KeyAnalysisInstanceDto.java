package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class KeyAnalysisInstanceDto {
    private long id;
    private String ip;
    private int port;
    private String hostPort;
    private String roleDesc;
    private boolean slave;
    private long currItems;
}
