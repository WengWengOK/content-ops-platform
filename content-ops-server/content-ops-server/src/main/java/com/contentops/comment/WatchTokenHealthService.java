package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 监控作品的票据健康计算：票据年龄 + 预警等级。
 *
 * <p>预警阈值规则：
 * <ol>
 *   <li>默认取配置 {@code token-warn-days}；</li>
 *   <li>若该监控已观测到真实有效期（{@code observedTtlDays}），取
 *       {@code min(配置阈值, 观测有效期 × 0.8)}，让预警更贴近实际失效节奏。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class WatchTokenHealthService {

    private final CommentProperties properties;

    public void annotateAll(List<CommentWatch> watches) {
        if (watches == null) {
            return;
        }
        watches.forEach(this::annotate);
    }

    public void annotate(CommentWatch watch) {
        Integer ageDays = tokenAgeDays(watch);
        watch.setTokenAgeDays(ageDays);
        if ("EXPIRED".equals(watch.getTokenState())) {
            watch.setTokenWarning("EXPIRED");
        } else if (ageDays != null && ageDays >= effectiveWarnDays(watch)) {
            watch.setTokenWarning("EXPIRING");
        } else {
            watch.setTokenWarning(null);
        }
    }

    /** 票据已使用天数（以 token_set_at 为准，回退 created_at）。 */
    public Integer tokenAgeDays(CommentWatch watch) {
        LocalDateTime base = watch.getTokenSetAt() != null ? watch.getTokenSetAt()
                : (watch.getCreatedAt() != null ? watch.getCreatedAt() : null);
        if (base == null) {
            return null;
        }
        return (int) Duration.between(base, LocalDateTime.now()).toDays();
    }

    /** 实际生效的预警天数。 */
    public int effectiveWarnDays(CommentWatch watch) {
        int configured = Math.max(1, properties.getXiaohongshu().getTokenWarnDays());
        Integer observed = watch.getObservedTtlDays();
        if (observed != null && observed > 0) {
            return Math.max(1, Math.min(configured, (int) Math.round(observed * 0.8)));
        }
        return configured;
    }
}