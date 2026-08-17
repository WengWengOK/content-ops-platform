package com.contentops.common.dto;

import lombok.*;
import java.util.List;

/**
 * Structured output from the Content Creation Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentDraftResult {

    /** Article outline/framework */
    private ArticleOutline outline;

    /** Full draft text in Markdown */
    private String draftContent;

    /** Word count */
    private int wordCount;

    /** Suggested title variations */
    private List<String> titleVariations;

    /** Tags/keywords for the article */
    private List<String> tags;

    /** Summary for social sharing */
    private String summary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArticleOutline {
        private String introduction;
        private List<Section> sections;
        private String conclusion;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section {
        private String heading;
        private String keyPoints;
        private String example;
    }
}
