package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CommandCheckDetailRowDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private int instanceId;
    private String hostPort;
    private String createTime;
    private String command;
    private String message;
}
