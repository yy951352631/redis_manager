package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class InstanceAlertSaveRequestDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String alertConfig;
    private String alertValue;
    private String configInfo;
    private int compareType;
    private int checkCycle;
    private Integer importantLevel;
    private String instanceHostPort;
    private Long appId;
    private int type;
}
