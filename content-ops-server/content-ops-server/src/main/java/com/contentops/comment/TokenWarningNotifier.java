package com.contentops.comment;

import com.contentops.common.credential.CredentialService;
import com.contentops.common.observability.AlertForwardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 票据预警通知：把「票据即将过期 / 已失效」主动推送到飞书 / 企微机器人。
 *
 * <p>去重策略：同一监控项同一预警状态只提醒一次；超过 {@code remind-hours}（默认 24h）
 * 再次满足条件会重复提醒；状态升级（EXPIRING → EXPIRED）立即提醒；用户更新票据后通知记录清零，
 * 下次再触发可立即提醒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenWarningNotifier {

    private final CommentWatchRepository watchRepository;
    private final WatchTokenHealthService healthService;
    private final CommentProperties properties;
    private final AlertForwardingService alertForwardingService;
    private final CredentialService credentialService;

    /** 通知结果：候选数、实际通知数、因去重跳过数、消息内容、各渠道发送结果。 */
    public record NotifyResult(int candidates, int notified, int skipped, List<String> items,
                               Map<String, Object> channels) {
    }

    /** 按需通知：定时任务用 notifyIfNeeded(null,false)，运维面板「立即推送」用 force=true。 */
    public NotifyResult notifyIfNeeded(String ownerId, boolean force) {
        CommentProperties.TokenNotifyProperties config = properties.getTokenNotify();
        if (!config.isEnabled() && !force) {
            return new NotifyResult(0, 0, 0, List.of(), Map.of());
        }

        List<CommentWatch> watches = watchRepository.list(ownerId, 500);
        healthService.annotateAll(watches);
        Map<String, String> accountNames = accountNames(ownerId);

        int candidates = 0;
        int skipped = 0;
        List<String> lines = new ArrayList<>();
        List<CommentWatch> toMark = new ArrayList<>();
        int maxItems = Math.max(1, config.getMaxItems());

        for (CommentWatch watch : watches) {
            String warning = watch.getTokenWarning();
            if (warning == null) {
                continue;
            }
            candidates++;
            if (!force && !shouldNotify(watch, warning, config.getRemindHours())) {
                skipped++;
                continue;
            }
            if (lines.size() < maxItems) {
                lines.add(formatLine(watch, warning, accountNames));
            }
            toMark.add(watch);
        }

        if (lines.isEmpty()) {
            return new NotifyResult(candidates, 0, skipped, List.of(), Map.of());
        }

        List<String> body = new ArrayList<>(lines);
        if (candidates > lines.size()) {
            body.add("… 其余 " + (candidates - lines.size()) + " 条见「运维总览 → 票据预警」");
        }
        body.add("处理入口：评论助手 → 运维总览 → 就地更新票据（更新后状态与提醒记录自动重置）");
        String title = "⚠️【ContentOps 票据预警】" + candidates + " 个监控作品的 xsec_token 需要处理";

        Map<String, Object> channels;
        try {
            channels = alertForwardingService.sendBusinessNotification(title, body);
        } catch (Exception e) {
            log.warn("[Comment] 票据预警推送失败: {}", e.getMessage());
            channels = Map.of("error", String.valueOf(e.getMessage()));
        }
        for (CommentWatch watch : toMark) {
            watchRepository.markTokenNotified(watch.getWatchId(), watch.getTokenWarning());
        }
        log.info("[Comment] 票据预警已推送: 候选={}, 通知={}, 跳过={}, 渠道={}",
                candidates, toMark.size(), skipped, channels.keySet());
        return new NotifyResult(candidates, toMark.size(), skipped, body, channels);
    }

    /** 是否需要提醒：状态变化立即提醒；同状态在 remindHours 内不重复。 */
    private boolean shouldNotify(CommentWatch watch, String warning, int remindHours) {
        String lastState = watch.getTokenNotifiedState();
        if (!warning.equals(lastState)) {
            return true;
        }
        LocalDateTime lastAt = watch.getTokenNotifiedAt();
        if (lastAt == null) {
            return true;
        }
        long hours = Duration.between(lastAt, LocalDateTime.now()).toHours();
        return hours >= Math.max(1, remindHours);
    }

    private String formatLine(CommentWatch watch, String warning, Map<String, String> accountNames) {
        String account = accountNames.getOrDefault(
                watch.getCredentialId() == null ? "" : watch.getCredentialId(), "全局配置");
        if ("EXPIRED".equals(warning)) {
            return "· " + account + " / " + watch.getWorkId() + "：票据已失效"
                    + (watch.getObservedTtlDays() != null
                    ? "（观测有效期 " + watch.getObservedTtlDays() + " 天）" : "")
                    + "，采集已降级为模拟数据";
        }
        return "· " + account + " / " + watch.getWorkId() + "：票据即将过期（已用 "
                + watch.getTokenAgeDays() + " 天，预警阈值 " + healthService.effectiveWarnDays(watch)
                + " 天），建议尽快更新";
    }

    private Map<String, String> accountNames(String ownerId) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            for (CredentialService.CredentialHealth health : credentialService.healthList(ownerId)) {
                map.put(health.credentialId(), health.accountName());
            }
        } catch (Exception e) {
            log.debug("[Comment] 读取账号名失败（忽略）: {}", e.getMessage());
        }
        return map;
    }
}