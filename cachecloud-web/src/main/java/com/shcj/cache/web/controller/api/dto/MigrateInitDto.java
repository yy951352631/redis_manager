package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MigrateInitDto {
    private List<MigrateMachineOptionDto> machines = new ArrayList<>();
    private List<MigrateToolOptionDto> tools = new ArrayList<>();
    private List<MigrateAppOptionDto> apps = new ArrayList<>();
    private Long importId;
    private Long targetAppId;
    private String sourceServers;
    private String redisSourcePass;
    private Integer sourceType;
    private Integer sourceDataType;
    private String redisSourceVersion;
}
