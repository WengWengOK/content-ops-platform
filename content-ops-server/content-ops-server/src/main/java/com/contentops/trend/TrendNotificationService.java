package com.contentops.trend;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 实时通知：消费轮询落库的突发热点事件。
 *
 * <ul>
 *   <li>WebSocket：把事件批量推送给所有在线前端（/ws/trends）；</li>
 *   <li>邮件：SMTP 与收件人配置齐全时异步发送摘要，否则记日志降级，不影响轮询。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendNotificationService {

    private final TrendWebSocketHandler webSocketHandler;
    private final TrendProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    /** 突发热点事件 → WebSocket 推送 + 邮件通知 */
    public void notifyBursts(List<TrendBurstEvent> events) {
        if (!properties.getNotifications().isEnabled() || events == null || events.isEmpty()) {
            return;
        }
        broadcast(events);
        sendEmailDigest(events);
    }

    private void broadcast(List<TrendBurstEvent> events) {
        try {
            Map<String, Object> payload = Map.of(
                    "type", "bursts",
                    "total", events.size(),
                    "data", events);
            webSocketHandler.broadcast(objectMapper.writeValueAsString(payload));
            log.info("[Trend-Notify] WebSocket 推送完成: events={}, online={}",
                    events.size(), webSocketHandler.connectedCount());
        } catch (Exception e) {
            log.warn("[Trend-Notify] WebSocket 推送失败: {}", e.getMessage());
        }
    }

    private void sendEmailDigest(List<TrendBurstEvent> events) {
        TrendProperties.Notifications.Email email = properties.getNotifications().getEmail();
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (!email.isEnabled() || email.getRecipients() == null || email.getRecipients().isBlank()) {
            log.info("[Trend-Notify] 邮件通知未配置（SMTP/收件人为空），跳过。"
                    + "配置 spring.mail.host 与 contentops.notifications.email.recipients 后自动启用");
            return;
        }
        if (mailSender == null) {
            log.warn("[Trend-Notify] SMTP 未配置（spring.mail.host 为空），跳过邮件发送");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                StringBuilder body = new StringBuilder("检测到 ")
                        .append(events.size()).append(" 条突发热点：\n\n");
                for (TrendBurstEvent e : events) {
                    body.append("【").append(label(e.getBurstLabel())).append("】")
                            .append(e.getTitle()).append("\n")
                            .append("  平台：").append(e.getPlatform());
                    if (e.getHeat() != null) {
                        body.append(" | 热度：").append(e.getHeat());
                    }
                    if (e.getHeatDelta() != null && e.getHeatDelta() > 0) {
                        body.append("（+").append(e.getHeatDelta()).append("）");
                    }
                    if (e.getRankDelta() != null && e.getRankDelta() > 0) {
                        body.append(" | 排名 ↑").append(e.getRankDelta());
                    }
                    if (e.getUrl() != null && !e.getUrl().isBlank()) {
                        body.append("\n  链接：").append(e.getUrl());
                    }
                    body.append("\n\n");
                }
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(email.getFrom());
                message.setTo(email.getRecipients().split(","));
                message.setSubject("【内容平台】" + events.size() + " 条突发热点");
                message.setText(body.toString());
                mailSender.send(message);
                log.info("[Trend-Notify] 邮件通知已发送: recipients={}, events={}",
                        email.getRecipients(), events.size());
            } catch (Exception e) {
                log.warn("[Trend-Notify] 邮件发送失败（降级）: {}", e.getMessage());
            }
        });
    }

    private String label(String burstLabel) {
        return switch (burstLabel == null ? "" : burstLabel) {
            case "飙升" -> "🔥 飙升";
            case "新上榜" -> "🆕 新上榜";
            case "上升" -> "↑ 上升";
            default -> burstLabel == null ? "" : burstLabel;
        };
    }

    /** 通知状态（前端连接指示灯用） */
    public Map<String, Object> status() {
        TrendProperties.Notifications.Email email = properties.getNotifications().getEmail();
        return Map.of(
                "enabled", properties.getNotifications().isEnabled(),
                "wsConnected", webSocketHandler.connectedCount(),
                "emailConfigured", mailSenderProvider.getIfAvailable() != null
                        && email.isEnabled()
                        && email.getRecipients() != null
                        && !email.getRecipients().isBlank());
    }
}
