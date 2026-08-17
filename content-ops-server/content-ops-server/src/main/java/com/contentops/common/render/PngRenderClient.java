package com.contentops.common.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 HTML→PNG 渲染服务（contentops-render-service）批量截图。
 *
 * <p>任何异常（连接失败/超时/非 200）都只记 WARN 并返回空结果，
 * 由调用方降级为纯 HTML ZIP，绝不阻塞下载主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PngRenderClient {

    private final RenderServiceProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 需要渲染的单个 HTML 画板。 */
    public record RenderEntry(String name, String html, int width, int height) {
    }

    /**
     * 批量渲染：返回 name → PNG 字节。渲染服务不可用时返回空 Map。
     */
    public Map<String, byte[]> renderPngs(List<RenderEntry> entries) {
        Map<String, byte[]> result = new HashMap<>();
        if (!properties.isEnabled() || entries == null || entries.isEmpty()) {
            return result;
        }
        try {
            List<Map<String, Object>> files = new ArrayList<>();
            for (RenderEntry entry : entries) {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("name", entry.name());
                file.put("html", entry.html());
                file.put("width", entry.width());
                file.put("height", entry.height());
                files.add(file);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("files", files);

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(properties.getUrl().replaceAll("/+$", "") + "/render"))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[PngRender] render service returned {}: {}", response.statusCode(),
                        truncate(response.body(), 200));
                return result;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("ok").asBoolean(false)) {
                log.warn("[PngRender] render service failed: {}", truncate(response.body(), 200));
                return result;
            }
            for (JsonNode file : root.path("files")) {
                String name = file.path("name").asText();
                String base64 = file.path("pngBase64").asText();
                if (!name.isBlank() && !base64.isBlank()) {
                    result.put(name, Base64.getDecoder().decode(base64));
                }
            }
            log.info("[PngRender] rendered {} PNG(s) via render service", result.size());
        } catch (Exception e) {
            log.warn("[PngRender] render service unavailable, fallback to HTML-only ZIP: {}",
                    e.getMessage());
        }
        return result;
    }

    /**
     * 批量测量卡片溢出：返回 name → overflowPx（真实 DOM 测量）。
     * 渲染服务不可用时返回 null（由调用方回退估算）。
     */
    public Map<String, Integer> measureOverflow(List<RenderEntry> entries) {
        Map<String, Integer> result = new HashMap<>();
        if (!properties.isEnabled() || entries == null || entries.isEmpty()) {
            return result;
        }
        try {
            List<Map<String, Object>> files = new ArrayList<>();
            for (RenderEntry entry : entries) {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("name", entry.name());
                file.put("html", entry.html());
                file.put("width", entry.width());
                file.put("height", entry.height());
                files.add(file);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("files", files);

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(properties.getUrl().replaceAll("/+$", "") + "/measure"))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[PngRender] measure endpoint returned {}: {}",
                        response.statusCode(), truncate(response.body(), 200));
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("ok").asBoolean(false)) {
                log.warn("[PngRender] measure failed: {}", truncate(response.body(), 200));
                return null;
            }
            for (JsonNode file : root.path("files")) {
                result.put(file.path("name").asText(), file.path("overflowPx").asInt(0));
            }
            return result;
        } catch (Exception e) {
            log.warn("[PngRender] measure service unavailable, fallback to estimation: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * 单张卡片溢出测量便捷方法（测量服务不可用时返回 null）。
     */
    public Integer measureOverflow(String html, int width, int height) {
        Map<String, Integer> result = measureOverflow(List.of(
                new RenderEntry("card", html, width, height)));
        return result == null ? null : result.get("card");
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
