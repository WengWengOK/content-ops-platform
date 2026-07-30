package com.contentops.common.dto;

import lombok.*;
import java.util.List;

/**
 * Structured output from the Image Design Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageDesignResult {

    /** Generated image descriptions (prompts used) */
    private List<GeneratedImage> images;

    /** Cover images per platform */
    private List<PlatformCover> covers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeneratedImage {
        private String prompt;
        private String imageUrl;
        private String style;
        private String colorTone;
        private String position; // "header", "inline-1", "footer"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformCover {
        private String platform;    // "公众号", "小红书", "头条"
        private String imageUrl;
        private int width;
        private int height;
        private String format;      // "jpg", "png"
    }
}
