package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CommandCheckRequestDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String machineIps;
    private String podIp;
    private String command;
}
