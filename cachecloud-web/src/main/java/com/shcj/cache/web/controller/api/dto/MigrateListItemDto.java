package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class MigrateListItemDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private String migrateId;
    private int migrateTool;
    private String migrateMachineIp;
    private long sourceAppId;
    private long targetAppId;
    private String sourceAppName;
    private String targetAppName;
    private String sourceServers;
    private String targetServers;
    private String userName;
    private int status;
    private String statusDesc;
    private String startTime;
    private String endTime;
    private String migrateToolLabel;
    private String migrateMachine;
    private String sourceMigrateTypeDesc;
    private String targetMigrateTypeDesc;
    private String redisSourceVersion;
    private String redisTargetVersion;
}
