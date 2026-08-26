package com.shcj.cache.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 一次集群风险评估的报告主体。
 */
@Data
public class RiskAssessReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private long appId;
    private String appName;

    /** 评估窗口小时数：24 或 168 */
    private int windowHours;

    /** 窗口起止，展示与复核用 */
    private Date windowStart;
    private Date windowEnd;

    /** 总评等级：NORMAL/ATTENTION/RISK/SEVERE */
    private String level;

    /** 0-100，仅用于排序与趋势，不参与判定 */
    private int score;

    /** 参与总评的维度数 / 维度总数，用于界面提示「基于 11/14 个维度」 */
    private int evaluatedDimensions;
    private int totalDimensions;

    /** 实例总数与采集成功数，采集不全时倾斜类结论需谨慎解读 */
    private int instanceCount;
    private int collectedInstanceCount;

    private String operator;
    private long costMs;
    private Date createTime;
}
