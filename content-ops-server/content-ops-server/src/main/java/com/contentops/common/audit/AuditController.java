package com.contentops.common.audit;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.security.RequireRole;
import com.contentops.common.security.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 操作审计查询接口（GET /api/v1/observability/audit）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor
@Tag(name = "操作审计")
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/audit")
    @Operation(summary = "操作审计记录（按用户/动作过滤）")
    @RequireRole(UserRole.ADMIN)
    public AgentResponse<Map<String, Object>> audit(
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        int safeLimit = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<Map<String, Object>> rows = auditService.find(ownerId, action, safeLimit);
        return AgentResponse.success("audit", Map.of("total", rows.size(), "audit", rows));
    }
}
