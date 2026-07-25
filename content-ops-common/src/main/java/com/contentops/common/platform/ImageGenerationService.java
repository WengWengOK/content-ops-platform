package com.contentops.common.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Image generation service using OpenAI DALL-E API.
 *
 * <p>This service replaces the mock image tools with real AI image generation.
 * It calls the OpenAI Images API ({@code /v1/images/generations}) to generate
 * images from text prompts.
 *
 * <p>If no API key is configured, methods return a graceful fallback message
 * instead of throwing — agents continue working with reduced capability.
 *
 * <p>Supported models:
 * <ul>
 *   <li>{@code dall-e-3} — highest quality, supports 1024x1024, 1792x1024, 1024x1792</li>
 *   <li>{@code dall-e-2} — faster, supports 256x256, 512x512, 1024x1024</li>
 * </ul>
 */
@Slf4j
@Component
public class ImageGenerationService {

    private final PlatformApiProperties.ImageGenerationConfig config;
    private final RestClient restClient;

    public ImageGenerationService(PlatformApiProperties properties) {
        this.config = properties.getImageGeneration();
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        if (!isAvailable()) {
            log.warn("Image generation API key not configured — image tools will return fallback messages. " +
                    "Set contentops.platform.image-generation.api-key to enable real image generation.");
        } else {
            log.info("ImageGenerationService initialized: model={}, size={}", config.getModel(), config.getSize());
        }
    }

    /**
     * Check whether the image generation API key is configured.
     */
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank()
                && !"sk-placeholder".equals(config.getApiKey());
    }

    /**
     * Generate an image from a text prompt.
     *
     * @param prompt      the text description of the image to generate
     * @param size        image size (e.g. "1024x1024", "1792x1024", "1024x1792"); null uses default
     * @param quality     image quality ("standard" or "hd"); null uses default
     * @return the generated image URL, or a fallback message if unavailable
     */
    public String generateImage(String prompt, String size, String quality) {
        if (!isAvailable()) {
            return "[图片生成不可用] OpenAI API Key 未配置。请在 application.yml 中设置 " +
                    "contentops.platform.image-generation.api-key。提示词: " + truncate(prompt, 100);
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getModel());
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", (size != null && !size.isBlank()) ? size : config.getSize());
            body.put("response_format", "url");
            if (quality != null && !quality.isBlank()) {
                body.put("quality", quality);
            } else {
                body.put("quality", config.getQuality());
            }
            if ("dall-e-3".equals(config.getModel())) {
                body.put("style", config.getStyle());
            }

            ImageApiResponse response = restClient.post()
                    .uri("/v1/images/generations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ImageApiResponse.class);

            if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                String imageUrl = response.getData().get(0).getUrl();
                log.info("Image generated successfully, URL length: {}", imageUrl != null ? imageUrl.length() : 0);
                return imageUrl;
            }
            return "[图片生成失败] API 未返回图片URL。提示词: " + truncate(prompt, 100);
        } catch (Exception e) {
            log.error("Image generation failed for prompt: {}", truncate(prompt, 80), e);
            return "[图片生成失败] 提示词: " + truncate(prompt, 80) + "，错误: " + e.getMessage();
        }
    }

    /**
     * Generate an image using default size and quality settings.
     *
     * @param prompt the text description of the image
     * @return the generated image URL, or a fallback message
     */
    public String generateImage(String prompt) {
        return generateImage(prompt, null, null);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    // ──────────────────── OpenAI Image API Response DTOs ────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageApiResponse {
        @JsonProperty("created")
        private long created;

        @JsonProperty("data")
        private List<ImageData> data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageData {
        @JsonProperty("url")
        private String url;

        @JsonProperty("revised_prompt")
        private String revisedPrompt;
    }
}
