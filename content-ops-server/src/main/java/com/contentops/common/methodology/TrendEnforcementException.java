package com.contentops.common.methodology;

/**
 * 趋势强制校验异常（P2 优化: 硬强制模式）。
 *
 * <p>当 {@link TrendAggregationProperties#isHardEnforce()} 为 true 时，
 * 趋势覆盖校验失败将抛出此异常，阻止不合格的分析报告输出。
 *
 * <p>异常消息包含校验失败的具体原因，便于调用方进行日志记录和用户反馈。
 *
 * @see TrendAggregationEnforcer
 * @see TrendAggregationProperties
 */
public class TrendEnforcementException extends RuntimeException {

    /** 校验结果，包含详细的命中/缺失信息 */
    private final TrendAggregationEnforcer.ValidationResult validationResult;

    /**
     * 构造趋势强制校验异常。
     *
     * @param message          异常消息
     * @param validationResult 校验结果
     */
    public TrendEnforcementException(String message,
                                      TrendAggregationEnforcer.ValidationResult validationResult) {
        super(message);
        this.validationResult = validationResult;
    }

    /**
     * 获取校验结果，包含命中/缺失关键词、insights 条数等详细信息。
     *
     * @return 校验结果
     */
    public TrendAggregationEnforcer.ValidationResult getValidationResult() {
        return validationResult;
    }
}
