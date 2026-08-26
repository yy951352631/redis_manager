package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次评估的完整报告
 */
@Data
public class RiskAssessReportDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long reportId;
    private Long appId;
    private String appName;
    private int windowHours;
    private String windowStart;
    private String windowEnd;
    private String level;
    private String levelLabel;
    private int score;
    private int evaluatedDimensions;
    private int totalDimensions;
    private int instanceCount;
    private int collectedInstanceCount;
    private String operator;
    private long costMs;
    private String createTime;

    private List<RiskAssessDimensionDto> dimensions = new ArrayList<>();
}
