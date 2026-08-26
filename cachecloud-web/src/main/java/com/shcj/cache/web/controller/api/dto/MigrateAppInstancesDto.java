package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class MigrateAppInstancesDto {
    private String instances;
    private String password;
    private int appType;
    private String appName;
    private String redisVersion;
}
