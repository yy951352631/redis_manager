package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ConfigCheckDetailRowDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private int instanceId;
    private String hostPort;
    private String versionName;
    private long appId;
    private String appName;
    private String createTime;
    private String configName;
    private int compareType;
    private String compareTypeLabel;
    private String expectValue;
    private String realValue;
}
