package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class TaskFlowDetailDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long taskId;
    private long appId;
    private String appName;
    private String className;
    private int status;
    private String statusDesc;
    private String progress;
    private double progressValue;
    private String prettyParam;
    private String currentStep;
    private List<TaskFlowStepDto> steps = new ArrayList<>();
}
