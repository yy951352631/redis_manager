package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ConfigCheckRequestDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long appId;
    private Integer versionId;
    private String configName;
    private Integer compareType;
    private String expectValue;
}
