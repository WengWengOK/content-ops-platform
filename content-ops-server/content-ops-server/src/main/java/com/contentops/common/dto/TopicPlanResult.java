package com.contentops.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "选题规划结果 — TopicAgent 的结构化输出，包含推荐选题列表、热门关键词、竞品分析和推荐方向")
public class TopicPlanResult {

    @Schema(description = "推荐选题列表，按预估互动率排序")
    private List<TopicCandidate> topics;

    @Schema(description = "调研发现的热门关键词列表")
    private List<String> trendingKeywords;

    @Schema(description = "竞品分析摘要")
    private String competitiveAnalysis;

    @Schema(description = "推荐方向及理由说明")
    private String recommendedDirection;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "选题候选 — 单个推荐选题的详细信息")
    public static class TopicCandidate {
        @Schema(description = "选题标题", example = "职场新人如何高效管理碎片时间")
        private String title;
        @Schema(description = "切入角度", example = "从通勤场景切入，聚焦15分钟微习惯")
        private String angle;
        @Schema(description = "选题理由", example = "碎片时间管理是职场新人高频痛点，且易于实操落地")
        private String rationale;
        @Schema(description = "预估互动率（0.0-1.0）", example = "0.85", minimum = "0", maximum = "1")
        private double estimatedEngagement;
        @Schema(description = "关联关键词列表")
        private List<String> keywords;
        @Schema(description = "各平台适配标题，key 为平台名称，value 为适配后的标题")
        private Map<String, String> platformAdaptations;
    }
}
