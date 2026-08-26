package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 报告中的单个维度结论
 */
@Data
public class RiskAssessDimensionDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String dimension;
    private String dimensionName;
    private String level;
    private String levelLabel;
    private Double actualValue;
    private Double threshold;
    private Long instanceId;
    private String instanceHostPort;
    private Integer sampleCount;
    private String sampleWindow;
    private String summary;
    private String suggestion;
    /** 证据 JSON 原样透出，前端按维度自行渲染 */
    private String evidence;
}
