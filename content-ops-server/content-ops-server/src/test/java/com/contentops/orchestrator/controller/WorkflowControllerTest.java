package com.contentops.orchestrator.controller;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.exception.GlobalExceptionHandler;
import com.contentops.common.platform.PlatformSpecRegistry;
import com.contentops.common.upload.FileStorageService;
import com.contentops.orchestrator.gateway.AgentGateway;
import com.contentops.orchestrator.service.WorkflowService;
import com.contentops.topic.agent.DiscussionAgent;
import com.contentops.topic.service.DiscussionSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WorkflowController 集成测试（MockMvc standalone 模式）。
 *
 * <p>验证：
 * <ul>
 *   <li>P0-5 全局异常处理器对 404/400/500 的统一响应格式</li>
 *   <li>API 端点基本可用性</li>
 *   <li>请求体校验（P1-10 @Valid）</li>
 * </ul>
 *
 * <p>使用 standalone MockMvc 避免 @WebMvcTest 的 SpringBootConfiguration 搜索问题。
 */
@DisplayName("WorkflowController API 测试")
@ExtendWith(MockitoExtension.class)
class WorkflowControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WorkflowService workflowService;

    @Mock
    private AgentGateway agentGateway;

    @Mock
    private PlatformSpecRegistry platformSpecRegistry;

    @Mock
    private DiscussionSessionService discussionSessionService;

    @Mock
    private DiscussionAgent discussionAgent;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private com.contentops.common.event.WorkflowEventBroadcaster workflowEventBroadcaster;

    @Mock
    private com.contentops.common.audit.AuditService auditService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new WorkflowController(workflowService, agentGateway, platformSpecRegistry,
                                discussionSessionService, discussionAgent, workflowEventBroadcaster,
                                auditService, fileStorageService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ════════════════ 正常流程测试 ════════════════

    @Test
    @DisplayName("GET /api/v1/workflow/stages 应返回4个主流水线阶段")
    void getStages_shouldReturnAllStages() throws Exception {
        mockMvc.perform(get("/api/v1/workflow/stages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(4));
    }

    @Test
    @DisplayName("POST /api/v1/workflow/{id}/select-platforms 应调用服务并返回成功")
    @SuppressWarnings("unchecked")
    void selectPlatforms_shouldCallService() throws Exception {
        TaskContext ctx = TaskContext.builder()
                .workflowId("wf-001")
                .currentStage(AgentStage.TOPIC_PLANNING.getCode())
                .status(TaskStatus.AWAITING_HUMAN.name())
                .createdAt(LocalDateTime.now())
                .build();
        when(workflowService.getWorkflowStatus("wf-001")).thenReturn(ctx);

        mockMvc.perform(post("/api/v1/workflow/wf-001/select-platforms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platforms\":[\"小红书\",\"微信公众号\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(workflowService).selectPlatforms(eq("wf-001"), anyList(), anyMap());
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
    @DisplayName("GET /api/v1/workflow/{id}/status 不存在时应返回 404")
    void getWorkflowStatus_notFound_shouldReturn404() throws Exception {
        when(workflowService.getWorkflowStatus("nonexistent")).thenReturn(null);

        mockMvc.perform(get("/api/v1/workflow/nonexistent/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/v1/workflow 列表应返回分页结构")
    void listWorkflows_shouldReturnPaginatedResult() throws Exception {
        when(workflowService.listAllWorkflows()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/workflow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("GET /api/v1/workflow?page=1&size=5 应返回指定分页参数")
    void listWorkflows_withPagination_shouldRespectParams() throws Exception {
        when(workflowService.listAllWorkflows()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/workflow").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5));
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
