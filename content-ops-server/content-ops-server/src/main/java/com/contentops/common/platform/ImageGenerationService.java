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
            // 降级：返回确定性占位图 URL，保证流水线可跑通（接入真实图片 API 后自动替换）
            String seed = Integer.toHexString(prompt.hashCode());
            return "https://picsum.photos/seed/contentops-" + seed + "/1080/1440";
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getModel());
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", (size != null && !size.isBlank()) ? size : config.getSize());
            body.put("response_format", "url");
            // 火山引擎 ARK 等 OpenAI 兼容接口不识别 quality/style，仅 DALL-E 系列发送
            if (config.getModel() != null && config.getModel().toLowerCase(java.util.Locale.ROOT).contains("dall-e")) {
                body.put("quality", quality != null && !quality.isBlank() ? quality : config.getQuality());
                body.put("style", config.getStyle());
            }

            ImageApiResponse response = restClient.post()
                    .uri(config.getPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ImageApiResponse.class);

            if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                ImageData imageData = response.getData().get(0);
                String imageUrl = imageData.getUrl();
                if ((imageUrl == null || imageUrl.isBlank())
                        && imageData.getB64Json() != null && !imageData.getB64Json().isBlank()) {
                    // 部分服务返回 base64，包装为 data URI 供前端直接展示
                    imageUrl = "data:image/png;base64," + imageData.getB64Json();
                }
                log.info("Image generated successfully, URL length: {}", imageUrl != null ? imageUrl.length() : 0);
                return imageUrl;
            }
            return "[图片生成失败] API 未返回图片URL。提示词: " + truncate(prompt, 100);
        } catch (Exception e) {
            String error = e.getMessage() == null ? "" : e.getMessage();
            // 火山引擎 ARK：模型未开通/未授权（ModelNotOpen / InvalidEndpointOrModel）
            // 这类错误不是代码问题，且短期内无法在服务端修复——降级为占位图，
            // 保证作品流水线仍可完整产出，用户开通模型后自动切回真实出图。
            boolean modelNotOpen = error.contains("ModelNotOpen")
                    || error.contains("InvalidEndpointOrModel")
                    || error.contains("not been activated")
                    || error.contains("does not exist or you do not have access");
            if (modelNotOpen) {
                log.warn("Image model not activated for key (fallback to placeholder). model={}, error={}",
                        config.getModel(), truncate(error, 120));
                String seed = Integer.toHexString(prompt.hashCode());
                return "https://picsum.photos/seed/contentops-" + seed + "/1080/1440"
                        + "#模型未开通-请在火山方舟控制台开通Seedream模型";
            }
            log.error("Image generation failed for prompt: {}", truncate(prompt, 80), e);
            return "[图片生成失败] 提示词: " + truncate(prompt, 80) + "，错误: " + truncate(error, 160);
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

        @JsonProperty("b64_json")
        private String b64Json;

        @JsonProperty("revised_prompt")
        private String revisedPrompt;
    }
}
