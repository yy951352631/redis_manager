package com.shcj.cache.risk.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个维度的评估结论。
 *
 * <p>证据字段是刻意的硬性要求：结论必须能被人工复核，否则调阈值时没有依据。
 */
@Data
public class DimensionResult {

    private RiskDimension dimension;
    private RiskLevel level = RiskLevel.NORMAL;

    private Double actualValue;
    private Double threshold;

    /** 触发结论的实例 */
    private Long instanceId;
    private String instanceHostPort;

    private Integer sampleCount;
    private String sampleWindow;

    private String summary;
    private String suggestion;

    /** 明细证据，如倾斜维度下每个 master 的实测值 */
    private Map<String, Object> evidence = new LinkedHashMap<>();

    public static DimensionResult of(RiskDimension dimension, RiskLevel level, String summary) {
        DimensionResult result = new DimensionResult();
        result.setDimension(dimension);
        result.setLevel(level);
        result.setSummary(summary);
        return result;
    }

    public DimensionResult evidence(String key, Object value) {
        this.evidence.put(key, value);
        return this;
    }
}
