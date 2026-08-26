package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class InstanceAlertItemDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private String alertConfig;
    private String alertValue;
    private int compareType;
    private String compareInfo;
    private String configInfo;
    private int type;
    private long instanceId;
    private String instanceHostPort;
    private int checkCycle;
    private Integer importantLevel;
    private String updateTime;
    private String lastCheckTime;
}
