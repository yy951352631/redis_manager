package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ConfigCheckRecordDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String key;
    private Integer versionId;
    private String versionName;
    private String userName;
    private String createTime;
    private String configName;
    private int compareType;
    private String compareTypeLabel;
    private String expectValue;
    private boolean success;
}
