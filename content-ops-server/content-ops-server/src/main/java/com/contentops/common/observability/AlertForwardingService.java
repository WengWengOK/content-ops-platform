package com.contentops.common.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 告警转发：把 Grafana Webhook 回调转发到飞书 / 企业微信自定义机器人。
 *
 * <p>异步发送，超时 5 秒；发送失败只记日志，不影响对 Grafana 的 200 响应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertForwardingService {

    private final AlertForwardingProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 解析并转发告警 payload 到已配置的机器人；均未配置时跳过。
     */
    public void forward(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        String status = String.valueOf(payload.getOrDefault("status", "unknown"));
        String alertname = "";
        String summary = "";
        String severity = "";
        String generatorUrl = "";
        Object alerts = payload.get("alerts");
        if (alerts instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object labels = first.get("labels");
            if (labels instanceof Map<?, ?> l) {
                alertname = getStr(l, "alertname");
                severity = getStr(l, "severity");
            }
            Object annotations = first.get("annotations");
            if (annotations instanceof Map<?, ?> ann) {
                summary = getStr(ann, "summary");
            }
            generatorUrl = getStr(first, "generatorURL");
        }
        String title = summary.isBlank() ? (alertname.isBlank() ? "LLM 告警" : alertname) : summary;
        String icon = "resolved".equalsIgnoreCase(status) ? "✅" : "🚨";
        String level = severity.isBlank() ? "" : "（" + severity + "）";

        boolean feishuConfigured = properties.getFeishuWebhook() != null
                && !properties.getFeishuWebhook().isBlank();
        boolean wecomConfigured = properties.getWecomWebhook() != null
                && !properties.getWecomWebhook().isBlank();
        if (!feishuConfigured && !wecomConfigured) {
            log.info("[Alerting] 未配置飞书/企微机器人，跳过转发（配置 contentops.observability.alerts.* 后自动启用）");
            return;
        }

        String feishuText = icon + "【ContentOps 告警】" + title + level + "\n"
                + "状态：" + status + "\n"
                + "规则：" + alertname + "\n"
                + (generatorUrl.isBlank() ? "" : "详情：" + generatorUrl + "\n");
        String wecomMarkdown = "**" + icon + " ContentOps 告警：" + title + "**" + level + "\n"
                + "> 状态：<font color=\"" + ("resolved".equalsIgnoreCase(status) ? "info" : "warning") + "\">" + status + "</font>\n"
                + "> 规则：" + alertname + "\n"
                + (generatorUrl.isBlank() ? "" : "> [查看详情](" + generatorUrl + ")\n");

        if (feishuConfigured) {
            sendAsync("飞书", properties.getFeishuWebhook(),
                    Map.of("msg_type", "text", "content", Map.of("text", feishuText)));
        }
        if (wecomConfigured) {
            sendAsync("企业微信", properties.getWecomWebhook(),
                    Map.of("msgtype", "markdown", "markdown", Map.of("content", wecomMarkdown)));
        }
    }

    /**
     * 发送一条测试消息到指定渠道（同步，返回是否成功），用于配置后一键验证。
     */
    public Map<String, Object> sendTest(String channel) {
        boolean feishu = "feishu".equalsIgnoreCase(channel);
        String url = feishu ? properties.getFeishuWebhook() : properties.getWecomWebhook();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", feishu ? "飞书" : "企业微信");
        if (url == null || url.isBlank()) {
            result.put("configured", false);
            result.put("message", "未配置 Webhook（设置 FEISHU_WEBHOOK / WECOM_WEBHOOK 后重启）");
            return result;
        }
        result.put("configured", true);
        result.put("url", maskUrl(url));
        String content = "✅【ContentOps 测试】告警转发链路已打通，本消息来自后端测试接口。";
        Map<String, Object> body = feishu
                ? Map.of("msg_type", "text", "content", Map.of("text", content))
                : Map.of("msgtype", "markdown", "markdown", Map.of("content", "**✅ ContentOps 测试**\n> 告警转发链路已打通，本消息来自后端测试接口。"));
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            result.put("httpStatus", response.statusCode());
            result.put("response", response.body() == null ? "" : response.body());
            result.put("success", response.statusCode() == 200);
            log.info("[Alerting] 测试消息发送: channel={}, http={}, resp={}",
                    channel, response.statusCode(),
                    response.body() == null ? "" : response.body().substring(0, Math.min(160, response.body().length())));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "发送失败: " + e.getMessage());
            log.warn("[Alerting] 测试消息发送失败: channel={}, err={}", channel, e.getMessage());
        }
        return result;
    }

    private String maskUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            String masked = path == null || path.isBlank()
                    ? "***" : path.substring(0, Math.min(12, path.length())) + "***";
            return URI.create(url).getScheme() + "://" + URI.create(url).getHost() + masked;
        } catch (Exception e) {
            return "***";
        }
    }

    private String getStr(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private void sendAsync(String channel, String url, Map<String, Object> body) {
        CompletableFuture.runAsync(() -> {
            try {
                String json = objectMapper.writeValueAsString(body);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                log.info("[Alerting] {} 转发完成: http={}, resp={}",
                        channel, response.statusCode(),
                        response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length())));
            } catch (Exception e) {
                log.warn("[Alerting] {} 转发失败（不影响告警回调）: {}", channel, e.getMessage());
            }
        });
    }
}
