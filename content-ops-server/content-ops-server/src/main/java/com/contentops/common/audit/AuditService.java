package com.contentops.common.audit;

import com.contentops.common.security.AuthContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 操作审计：关键业务变更留痕（谁/何时/做了什么/关联 traceId），供合规与排查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final Tracer tracer;

    private static final String SQL_INSERT =
            "INSERT INTO contentops_audit_log "
                    + "(audit_id, owner_id, action, target_type, target_id, detail, trace_id, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SQL_LIST =
            "SELECT audit_id, owner_id, action, target_type, target_id, detail, trace_id, created_at "
                    + "FROM contentops_audit_log "
                    + "WHERE (? IS NULL OR ? = '' OR owner_id = ?) "
                    + "  AND (? IS NULL OR ? = '' OR action = ?) "
                    + "ORDER BY created_at DESC LIMIT ?";

    /**
     * 记录一条审计日志（owner 缺省取当前登录用户）。
     */
    public void record(String action, String targetType, String targetId, String detail) {
        try {
            String owner = AuthContext.currentUserId() == null ? "anonymous" : AuthContext.currentUserId();
            String traceId = null;
            try {
                if (tracer.currentSpan() != null) {
                    traceId = tracer.currentSpan().context().traceId();
                }
            } catch (Exception ignored) {
                // 无追踪上下文
            }
            jdbcTemplate.update(SQL_INSERT,
                    UUID.randomUUID().toString(),
                    owner,
                    action,
                    targetType,
                    targetId,
                    detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000),
                    traceId,
                    Timestamp.valueOf(LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[Audit] 记录失败: action={}, err={}", action, e.getMessage());
        }
    }

    public List<Map<String, Object>> find(String ownerId, String action, int limit) {
        try {
            String o = ownerId == null ? "" : ownerId;
            String a = action == null ? "" : action;
            return jdbcTemplate.queryForList(SQL_LIST, o, o, o, a, a, a, limit);
        } catch (Exception e) {
            log.error("[Audit] 查询失败", e);
            return List.of();
        }
    }
}
