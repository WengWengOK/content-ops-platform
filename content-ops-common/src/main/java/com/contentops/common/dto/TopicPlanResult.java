package com.contentops.common.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

/**
 * Structured output from the Topic Planning Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicPlanResult {

    /** Recommended topics */
    private List<TopicCandidate> topics;

    /** Trending keywords discovered during research */
    private List<String> trendingKeywords;

    /** Competitive analysis summary */
    private String competitiveAnalysis;

    /** Recommended direction with rationale */
    private String recommendedDirection;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopicCandidate {
        private String title;
        private String angle;
        private String rationale;
        private double estimatedEngagement; // 0.0-1.0
        private List<String> keywords;
        private Map<String, String> platformAdaptations; // platform -> adapted title
    }
}
