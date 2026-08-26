package com.shcj.cache.risk.model;

/**
 * 单个维度的评估器。
 *
 * <p>实现只需关心自己的判定逻辑；窗口不足、类型不适用这两种情况由引擎统一处理，
 * 到达 {@link #evaluate} 时已保证窗口与类型是满足的。
 */
public interface RiskDimensionEvaluator {

    RiskDimension dimension();

    /**
     * @return 结论；无法得出时返回 {@link RiskLevel#INSUFFICIENT_DATA} 并写明原因
     */
    DimensionResult evaluate(RiskAssessContext context);
}
