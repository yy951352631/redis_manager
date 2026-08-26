package com.shcj.cache.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 风险评估的阈值规则：一个维度在一个等级上的判定边界。
 *
 * <p>与 {@code instance_alert_configs} 分开存放——告警要求低噪声、阈值偏高，评估要求分辨力、
 * 需要在正常与严重之间铺出中间档，共用一套会互相绑架。同名维度的默认值初始化时对齐告警阈值，
 * 避免出现「告警说没事、评估说严重」的矛盾。
 */
@Data
public class RiskAssessRule implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 维度标识，见 RiskDimension */
    private String dimension;

    /** 子项标识，如倾斜维度下的 memory/keys/cpu/qps/traffic；无子项时为空 */
    private String subItem;

    /** 命中该规则时判定的等级：ATTENTION/RISK/SEVERE */
    private String level;

    /** 比较符：GT/GTE/LT/LTE/EQ/NE */
    private String operator;

    /** 阈值 */
    private Double threshold;

    /** 阈值为文本时使用（如 rdb_last_bgsave_status=ok） */
    private String thresholdText;

    /** 参与判定所需的最小绝对值门槛，低于此值直接判正常（如倾斜维度的最小内存） */
    private Double minAbsolute;

    private int enabled;

    /** 该规则的说明，展示在报告证据里 */
    private String description;

    /** 命中后的静态处置建议 */
    private String suggestion;

    /** 是否已被人工修改过，报告中会标注 */
    private int customized;

    private Date updateTime;
}
