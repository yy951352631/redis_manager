package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个评估维度的判定规则，供「评估策略」抽屉展示。
 */
@Data
public class RiskRuleDimensionDto {

    /** 维度枚举名，如 MEMORY_USAGE */
    private String dimension;
    /** 维度中文名 */
    private String dimensionName;
    /** 该维度要求的最小评估窗口（小时），0 表示无要求 */
    private int minWindowHours;
    /** 是否仅适用于 redis-cluster */
    private boolean clusterOnly;
    /** 该维度没有任何评估器实现时为 true——声明了但尚未落地 */
    private boolean notImplemented;
    /** 指标含义与判定说明 */
    private String metricDesc;
    private List<RiskRuleItemDto> items = new ArrayList<RiskRuleItemDto>();

    @Data
    public static class RiskRuleItemDto {
        private String subItem;
        private String level;
        private String levelName;
        private String operator;
        /** 运算符的中文表述，如「大于」 */
        private String operatorName;
        private Double threshold;
        private Double minAbsolute;
        /** 分类型判定条件的原文；非阈值维度用它替代 operator + threshold 的拼装 */
        private String conditionText;
        private boolean enabled;
        private boolean customized;
        private String description;
        private String suggestion;
    }
}
