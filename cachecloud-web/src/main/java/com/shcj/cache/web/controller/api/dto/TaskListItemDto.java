package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class TaskListItemDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private long appId;
    private int clusterNo;
    private String className;
    private int status;
    private String statusDesc;
    private String progress;
    private double progressValue;
    private String createTime;
    private String startTime;
    private String endTime;
    private String costSeconds;
    private String executeIpPort;
    private boolean finished;
    private boolean running;
}
