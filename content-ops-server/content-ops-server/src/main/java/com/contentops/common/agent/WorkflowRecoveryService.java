package com.contentops.common.agent;

import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.service.WorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流崩溃恢复（Worker 韧性）：定时扫描停留在 IN_PROGRESS/RUNNING 且长时间未更新的
 * 工作流，重新提交执行。配合 PG 状态持久化，进程重启后中断的流水线可自动续跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowRecoveryService {

    private final WorkflowStateManager stateManager;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Value("${contentops.agents.recovery-stale-seconds:90}")
    private int staleSeconds;

    @Scheduled(
            fixedDelayString = "${contentops.agents.recovery-ms:60000}",
            initialDelayString = "${contentops.agents.recovery-initial-ms:30000}")
    public void recoverStaleWorkflows() {
        try {
            Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusSeconds(Math.max(10, staleSeconds)));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT workflow_id, context_json FROM contentops_workflow "
                            + "WHERE updated_at < ? ORDER BY updated_at ASC LIMIT 20",
                    cutoff);
            int recovered = 0;
            for (Map<String, Object> row : rows) {
                String workflowId = String.valueOf(row.get("workflow_id"));
                try {
                    JsonNode node = objectMapper.readTree(String.valueOf(row.get("context_json")));
                    String status = node.path("status").asText("");
                    if ("IN_PROGRESS".equals(status) || "RUNNING".equals(status)) {
                        workflowService.resumeWorkflow(workflowId);
                        recovered++;
                    }
                } catch (Exception e) {
                    log.warn("[Recovery] 解析失败: workflowId={}, err={}", workflowId, e.getMessage());
                }
            }
            if (recovered > 0) {
                log.warn("[Recovery] 扫描完成：恢复 {} 个中断工作流", recovered);
            }
        } catch (Exception e) {
            log.warn("[Recovery] 扫描失败: {}", e.getMessage());
        }
    }
}
