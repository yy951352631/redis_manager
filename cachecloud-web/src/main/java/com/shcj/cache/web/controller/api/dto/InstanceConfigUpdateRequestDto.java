package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class InstanceConfigUpdateRequestDto {
    private String configName;
    private String configValue;
}
