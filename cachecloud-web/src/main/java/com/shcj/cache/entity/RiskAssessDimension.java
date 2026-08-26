package com.shcj.cache.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * 报告中的单个维度结论。
 *
 * <p>拆成独立行而非塞进报告的大 JSON，是为了支持按维度查询历史趋势
 * （「这个集群的内存维度过去 30 天从正常滑到风险」），JSON blob 做不到这种查询。
 */
@Data
public class RiskAssessDimension implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private long reportId;
    private long appId;

    private String dimension;
    private String dimensionName;

    /** NORMAL/ATTENTION/RISK/SEVERE/INSUFFICIENT_DATA/WINDOW_TOO_SHORT/NOT_APPLICABLE */
    private String level;

    /** 触发判定的实测值 */
    private Double actualValue;

    /** 命中的阈值；阈值改动后回看旧报告仍能还原当时的判定标准 */
    private Double threshold;

    /** 触发该结论的实例，无具体实例时为空 */
    private Long instanceId;
    private String instanceHostPort;

    /** 样本时间窗口与样本数，供人工复核 */
    private Integer sampleCount;
    private String sampleWindow;

    /** 结论摘要 */
    private String summary;

    /** 静态处置建议 */
    private String suggestion;

    /** 明细证据 JSON（如倾斜维度下每个 master 的实测值） */
    private String evidence;
}
