package com.contentops.worker.internal;

import com.contentops.common.api.AgentExecuteRequest;
import com.contentops.common.api.AgentExecuteResponse;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.orchestrator.gateway.AgentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Phase3 Worker 对内 API 契约（只允许编排器调用，不对外暴露）。
 *
 * <p>第一阶段（渐进拆分）：Worker 内部仍复用本地 {@link AgentGateway}（6 个阶段同步方法），
 * 根据 stageCode switch 到对应 callXxx 方法。Orchestrator 真正改为 HTTP 调用到该端口后，
 * Worker 就是独立可水平扩容的节点。
 *
 * <p>契约：POST /internal/api/agent/execute → AgentExecuteResponse
 */
@Slf4j
@RestController
@RequestMapping("/internal/api/agent")
@RequiredArgsConstructor
public class WorkerInternalContractController {

    private final AgentGateway agentGateway;

    @PostMapping("/execute")
    public ResponseEntity<AgentExecuteResponse> execute(@RequestBody AgentExecuteRequest req) {
        log.info("[Worker-Internal] 收到执行请求: workflowId={}, taskId={}, stage={}",
                req.getWorkflowId(), req.getTaskId(), req.getStageCode());
        try {
            Map<String, Object> inputs = req.getInputs() == null ? Map.of() : req.getInputs();
            AgentTaskRequest task = AgentTaskRequest.builder()
                    .taskId(req.getTaskId())
                    .workflowId(req.getWorkflowId())
                    .stageCode(req.getStageCode())
                    .inputs(inputs)
                    .build();
            // 按 stageCode 路由到 AgentGateway 对应方法（6 个阶段 + 讨论/优化子阶段）
            AgentResponse<Map<String, Object>> resp = routeByStage(req.getStageCode(), task);
            if (resp == null) {
                return ResponseEntity.ok(AgentExecuteResponse.fail(req.getStageCode(), "AgentGateway 返回空响应"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (resp.getData() instanceof Map m) ? m :
                    (resp.getData() != null ? Map.of("value", resp.getData()) : Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (resp.getMetadata() instanceof Map m) ? m : Map.of();
            return ResponseEntity.ok(AgentExecuteResponse.ok(req.getStageCode(), data, meta));
        } catch (Exception e) {
            log.warn("[Worker-Internal] 执行失败: workflowId={}, stage={}, err={}",
                    req.getWorkflowId(), req.getStageCode(), e.getMessage(), e);
            return ResponseEntity.status(500).body(AgentExecuteResponse.fail(req.getStageCode(), e.getMessage()));
        }
    }

    // ──────────────────── stage → callXxx 路由 ────────────────────

    private AgentResponse<Map<String, Object>> routeByStage(String stageCode, AgentTaskRequest task) {
        if (stageCode == null) throw new IllegalArgumentException("stageCode 不能为空");
        return switch (stageCode) {
            case "topic-planning"         -> agentGateway.callTopic(task);
            case "content-creation"       -> agentGateway.callContentExecute(task);
            case "content-outline"        -> agentGateway.callContentOutline(task);
            case "content-draft"          -> agentGateway.callContentDraft(task);
            case "image-design"           -> agentGateway.callImageExecute(task);
            case "image-styles"           -> agentGateway.callImageStyles(task);
            case "image-generate"         -> agentGateway.callImageGenerate(task);
            case "publishing"             -> agentGateway.callPublish(task);
            case "data-analysis"          -> agentGateway.callAnalysis(task);
            case "optimization"           -> agentGateway.callOptimize(task);
            default -> throw new IllegalArgumentException("未知 stageCode: " + stageCode);
        };
    }
}
