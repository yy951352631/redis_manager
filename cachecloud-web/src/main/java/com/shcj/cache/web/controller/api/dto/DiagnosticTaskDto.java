package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class DiagnosticTaskDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private long taskId;
    private long parentTaskId;
    private long auditId;
    private int type;
    private int status;
    private long appId;
    private int clusterNo;
    private String appName;
    private String node;
    private String diagnosticCondition;
    private String redisKey;
    private int deleteStatus = -1;
    private long resultCount;
    private long cost;
    private String formatCostTime;
    private String createTime;
}
