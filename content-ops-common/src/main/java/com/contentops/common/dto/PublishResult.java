package com.contentops.common.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Structured output from the Publishing Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishResult {

    /** Published articles per platform */
    private List<PlatformPublication> publications;

    /** Overall publish status */
    private String status; // "SUCCESS", "PARTIAL", "FAILED"

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformPublication {
        private String platform;
        private String articleUrl;
        private String formattedContent;
        private String status; // "PUBLISHED", "DRAFT", "FAILED"
        private String failureReason;
        private LocalDateTime publishedAt;
        private Map<String, Object> platformMetadata;
    }
}
