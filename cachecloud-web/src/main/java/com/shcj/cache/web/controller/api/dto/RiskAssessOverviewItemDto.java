package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 总览清单的一行：集群 + 最近一次评估结果。
 *
 * <p>未评估过的集群 {@code level} 为 null，前端展示为「未评估」并给出一键入口。
 */
@Data
public class RiskAssessOverviewItemDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private long appId;
    private String appName;
    private String typeDesc;
    private String versionName;
    private int instanceCount;

    private Long reportId;
    private String level;
    private String levelLabel;
    private Integer score;
    private Integer windowHours;
    private Integer evaluatedDimensions;
    private Integer totalDimensions;
    private String lastAssessTime;
}
