package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class RestartRecordDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;
    private String userName;
    private Long appId;
    private String appName;
    private List<RestartRecordInstanceDto> instances = new ArrayList<>();
    private int operateType;
    private String operateTypeLabel;
    private List<String> logs = new ArrayList<>();
    private String startTime;
    private String endTime;
    private Integer status;
    private String statusLabel;
    private boolean stoppable;
}
