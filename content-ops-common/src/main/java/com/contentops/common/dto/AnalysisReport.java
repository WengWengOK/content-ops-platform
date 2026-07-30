package com.contentops.common.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

/**
 * Structured output from the Data Analysis Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisReport {

    /** Key metrics summary */
    private Map<String, Double> keyMetrics;

    /** Content performance by category */
    private List<CategoryPerformance> categoryPerformance;

    /** Best posting times */
    private List<TimeSlotPerformance> timeSlotPerformance;

    /** Trending insights */
    private List<String> insights;

    /** Actionable recommendations */
    private List<String> recommendations;

    /** Chart data for visualization (JSON-serializable) */
    private Map<String, Object> chartData;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryPerformance {
        private String category;
        private double avgReads;
        private double avgLikes;
        private double avgShares;
        private double engagementRate;
        private int articleCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSlotPerformance {
        private String dayOfWeek;
        private String timeRange;
        private double avgEngagement;
        int articleCount;
    }
}
