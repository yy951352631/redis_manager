package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CommandCheckRecordDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String key;
    private String userName;
    private String createTime;
    private String machineIps;
    private String podIp;
    private String command;
    private boolean success;
}
