package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppConfigConsistencyNodeDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private int instanceId;
    private String hostPort;
    private String group;
    private String role;
    private boolean success;
    private int configCount;
    private String message;
}
