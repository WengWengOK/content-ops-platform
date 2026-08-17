package com.contentops.common.dto;

import lombok.*;
import java.util.List;

/**
 * Structured output from the Optimization Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptimizationResult {

    /** Content strategy adjustments */
    private List<StrategyAdjustment> strategyAdjustments;

    /** Next cycle's recommended topics */
    private List<String> recommendedTopics;

    /** Key learnings from the cycle */
    private List<String> learnings;

    /** Overall health score (0-100) */
    private double healthScore;

    /** Summary of the optimization cycle */
    private String cycleSummary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StrategyAdjustment {
        private String dimension;    // "content_type", "posting_time", "platform_focus", "tone"
        private String currentValue;
        private String recommendedValue;
        private String rationale;
        private double expectedImpact; // 0.0-1.0
    }
}
