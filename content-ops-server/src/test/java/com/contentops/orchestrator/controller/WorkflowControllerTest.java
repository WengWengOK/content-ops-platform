package com.contentops.orchestrator.controller;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.exception.GlobalExceptionHandler;
import com.contentops.orchestrator.gateway.AgentGateway;
import com.contentops.orchestrator.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WorkflowController 集成测试（MockMvc）。
 *
 * <p>验证：
 * <ul>
 *   <li>P0-5 全局异常处理器对 404/400/500 的统一响应格式</li>
 *   <li>API 端点基本可用性</li>
 *   <li>请求体校验（P1-10 @Valid）</li>
 * </ul>
 */
@DisplayName("WorkflowController API 测试")
@WebMvcTest(WorkflowController.class)
@Import(GlobalExceptionHandler.class)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowService workflowService;

    @MockBean
    private AgentGateway agentGateway;

    // ════════════════ 正常流程测试 ════════════════

    @Test
    @DisplayName("GET /api/v1/workflow/stages 应返回6个阶段")
    void getStages_shouldReturnAllStages() throws Exception {
        mockMvc.perform(get("/api/v1/workflow/stages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(6));
    }

    @Test
    @DisplayName("GET /api/v1/workflow/{id}/status 存在时应返回工作流上下文")
    void getWorkflowStatus_existing_shouldReturnContext() throws Exception {
        TaskContext ctx = TaskContext.builder()
                .workflowId("wf-001")
                .currentStage(AgentStage.TOPIC_PLANNING.getCode())
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .build();

        when(workflowService.getWorkflowStatus("wf-001")).thenReturn(ctx);

        mockMvc.perform(get("/api/v1/workflow/wf-001/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.workflowId").value("wf-001"));
    }

    @Test
    @DisplayName("GET /api/v1/workflow/{id}/status 不存在时应返回 success=false")
    void getWorkflowStatus_notFound_shouldReturnFailure() throws Exception {
        when(workflowService.getWorkflowStatus("nonexistent")).thenReturn(null);

        mockMvc.perform(get("/api/v1/workflow/nonexistent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/v1/workflow 列表应返回数组")
    void listWorkflows_shouldReturnArray() throws Exception {
        when(workflowService.listAllWorkflows()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/workflow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ════════════════ 参数校验测试（P1-10） ════════════════

    @Test
    @DisplayName("POST /api/v1/workflow/start 缺少 accountProfile 应返回 400")
    void startWorkflow_missingAccountProfile_shouldReturn400() throws Exception {
        String emptyJson = "{}";

        mockMvc.perform(post("/api/v1/workflow/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/workflow/start 请求体格式错误应返回 400")
    void startWorkflow_malformedJson_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/workflow/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ════════════════ 全局异常处理器测试（P0-5） ════════════════

    @Test
    @DisplayName("访问不存在的端点应返回 404 + AgentResponse 格式")
    void unknownEndpoint_shouldReturn404WithAgentResponse() throws Exception {
        mockMvc.perform(get("/api/v1/workflow/nonexistent-endpoint"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("服务端 RuntimeException 应返回 500 + AgentResponse 格式")
    void runtimeException_shouldReturn500WithAgentResponse() throws Exception {
        when(workflowService.getWorkflowStatus("error-id"))
                .thenThrow(new RuntimeException("模拟内部错误"));

        mockMvc.perform(get("/api/v1/workflow/error-id/status"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists());
    }
}
