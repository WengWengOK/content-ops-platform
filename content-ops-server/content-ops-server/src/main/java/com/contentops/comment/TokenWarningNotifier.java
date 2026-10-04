package com.contentops.comment;

import com.contentops.common.credential.CredentialService;
import com.contentops.common.observability.AlertForwardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /** 前端基础地址：用于生成「打开评论助手」直达链接 */
    @Value("${contentops.web.base-url:http://localhost:5173}")
    private String webBaseUrl;

    /** 通知结果渠道与链接。 */
    public String actionUrl() {
        String base = webBaseUrl == null || webBaseUrl.isBlank() ? "http://localhost:5173" : webBaseUrl.trim();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/comments?tab=ops";
    }

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

        // 账号级令牌失效（AUTH_INVALID / ERROR）并入同一条预警
        List<String> accountLines = new ArrayList<>();
        List<CredentialService.CredentialHealth> accountToMark = new ArrayList<>();
        int accountCandidates = 0;
        for (CredentialService.CredentialHealth health : credentialService.healthList(ownerId)) {
            String state = health.tokenState();
            if (!"AUTH_INVALID".equals(state) && !"ERROR".equals(state)) {
                continue;
            }
            accountCandidates++;
            if (!force && !shouldNotifyAccount(health, state, config.getRemindHours())) {
                continue;
            }
            if (accountLines.size() < maxItems) {
                accountLines.add("· " + health.accountName() + "：" + ("ERROR".equals(state)
                        ? "桥服务不可达或令牌不可用，请检查 baseUrl 与 AUTH_TOKEN"
                        : "账号未登录/令牌失效，请在桥端重新扫码登录并更新 AUTH_TOKEN"));
            }
            accountToMark.add(health);
        }

        if (lines.isEmpty() && accountLines.isEmpty()) {
            // 全部被去重跳过：如实返回候选数与跳过数，便于面板/日志观察
            return new NotifyResult(candidates, 0, skipped, List.of(), Map.of());
        }

        List<String> body = new ArrayList<>();
        if (!lines.isEmpty()) {
            body.add("【笔记票据 xsec_token】");
            body.addAll(lines);
            if (candidates > lines.size()) {
                body.add("… 其余 " + (candidates - lines.size()) + " 条见「运维总览 → 票据预警」");
            }
        }
        if (!accountLines.isEmpty()) {
            if (!body.isEmpty()) {
                body.add("");
            }
            body.add("【账号令牌 AUTH_TOKEN】");
            body.addAll(accountLines);
            if (accountCandidates > accountLines.size()) {
                body.add("… 其余 " + (accountCandidates - accountLines.size()) + " 个账号见「运维总览 → 账号健康」");
            }
        }
        body.add("");
        body.add("处理入口：打开评论助手 → 运维总览；更新票据/令牌后状态与提醒记录会自动重置");

        int total = candidates + accountCandidates;
        String title = "⚠️【ContentOps 预警】" + total + " 项待处理（票据 " + candidates
                + " / 账号 " + accountCandidates + "）";

        Map<String, Object> channels;
        try {
            channels = alertForwardingService.sendBusinessCard(title, body, actionUrl(), "打开评论助手");
        } catch (Exception e) {
            log.warn("[Comment] 预警推送失败: {}", e.getMessage());
            channels = Map.of("error", String.valueOf(e.getMessage()));
        }
        for (CommentWatch watch : toMark) {
            watchRepository.markTokenNotified(watch.getWatchId(), watch.getTokenWarning());
        }
        for (CredentialService.CredentialHealth health : accountToMark) {
            credentialService.markTokenNotified(health.credentialId(), health.tokenState());
        }
        log.info("[Comment] 预警已推送: 票据候选={} 通知={}, 账号候选={} 通知={}, 跳过={}, 渠道={}",
                candidates, toMark.size(), accountCandidates, accountToMark.size(), skipped, channels.keySet());
        return new NotifyResult(total, toMark.size() + accountToMark.size(), skipped, body, channels);
    }

    /** 账号级预警去重：同状态在 remindHours 内不重复。 */
    private boolean shouldNotifyAccount(CredentialService.CredentialHealth health, String state,
                                        int remindHours) {
        if (!state.equals(health.tokenNotifiedState())) {
            return true;
        }
        LocalDateTime lastAt = health.tokenNotifiedAt();
        if (lastAt == null) {
            return true;
        }
        return Duration.between(lastAt, LocalDateTime.now()).toHours() >= Math.max(1, remindHours);
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