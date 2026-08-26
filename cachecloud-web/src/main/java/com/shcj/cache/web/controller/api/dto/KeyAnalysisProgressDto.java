package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class KeyAnalysisProgressDto {
    private long taskId;
    private double progress;
    private String progressText;
    private int done;
    private int total;
    private int status;
    private boolean finished;
    private String currentStep;
}
