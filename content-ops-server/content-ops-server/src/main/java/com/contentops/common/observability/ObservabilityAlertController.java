package com.contentops.common.observability;

import com.contentops.common.dto.AgentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Grafana Alerting Webhook 接收端（POST /api/v1/observability/alerts）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor
@Tag(name = "可观测性告警")
public class ObservabilityAlertController {

    private final AlertForwardingService alertForwardingService;

    /**
     * 告警触发/恢复时回调，当前记录日志，后续可扩展为飞书/邮件/钉钉转发。
     */
    @PostMapping("/alerts")
    @Operation(summary = "Grafana 告警回调接收端（Webhook）")
    public AgentResponse<Map<String, Object>> receiveAlerts(
            @RequestBody(required = false) Map<String, Object> payload) {
        try {
            String status = payload == null ? "unknown" : String.valueOf(payload.getOrDefault("status", "unknown"));
            String title = "";
            Object alerts = payload == null ? null : payload.get("alerts");
            if (alerts instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
                Object annotations = first.get("annotations");
                if (annotations instanceof Map<?, ?> ann) {
                    Object summary = ann.get("summary");
                    title = summary == null ? "" : String.valueOf(summary);
                }
            }
            String payloadPreview = payload == null
                    ? "{}"
                    : payload.toString().substring(0, Math.min(2000, payload.toString().length()));
            log.warn("[Alerting] Grafana 告警回调: status={}, title={}, payload={}", status, title, payloadPreview);
            alertForwardingService.forward(payload);
        } catch (Exception e) {
            log.warn("[Alerting] 告警回调解析失败: {}", e.getMessage());
        }
        return AgentResponse.success("observability", Map.of("received", true));
    }

    /**
     * 配置机器人后一键测试：向指定渠道发送一条测试消息。
     */
    @PostMapping("/alerts/test")
    @Operation(summary = "测试发送告警消息到飞书/企业微信机器人")
    public AgentResponse<Map<String, Object>> sendTest(
            @RequestParam(defaultValue = "feishu") String channel) {
        Map<String, Object> result = alertForwardingService.sendTest(channel);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return AgentResponse.success("observability", result);
        }
        return AgentResponse.failure("observability",
                String.valueOf(result.getOrDefault("message", "发送失败")));
    }
}
