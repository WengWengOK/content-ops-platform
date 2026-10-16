package com.contentops.common.agent.external;

import com.contentops.common.audit.AuditService;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.exception.BusinessException;
import com.contentops.common.rag.DocumentIngestionPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部 Agent（CrewAI）入口测试：开关校验、回写知识库、审计记录。
 */
class ExternalAgentControllerTest {

    private ExternalAgentProperties properties;
    private CrewAiResearchClient client;
    private DocumentIngestionPipeline ingestionPipeline;
    private AuditService auditService;
    private ExternalAgentController controller;

    @BeforeEach
    void setUp() {
        properties = new ExternalAgentProperties();
        properties.getCrewai().setEnabled(true);
        client = mock(CrewAiResearchClient.class);
        ingestionPipeline = mock(DocumentIngestionPipeline.class);
        auditService = mock(AuditService.class);
        controller = new ExternalAgentController(properties, client, ingestionPipeline, auditService);
    }

    private Map<String, Object> crewResult() {
        return Map.of(
                "topic", "AI 提效工具",
                "executor", "crewai",
                "hotAngles", List.of(Map.of("angle", "蹭发布会", "evidence", "热榜第 2", "source", "https://x")),
                "topicCandidates", List.of(Map.of("title", "标题 A", "platforms", List.of("XHS"))),
                "factCheck", Map.of("passed", true, "flags", List.of(), "note", "ok"),
                "evidenceDigest", Map.of("trends", "榜单摘要"),
                "reportMarkdown", "# 选题研究报告：AI 提效工具\n\n## 一、热点证据\n...");
    }

    @Test
    @DisplayName("未启用时直接拒绝（不调用外部服务）")
    void rejectsWhenDisabled() {
        properties.getCrewai().setEnabled(false);
        ExternalAgentController.ResearchRunRequest request = new ExternalAgentController.ResearchRunRequest();
        request.setTopic("AI 提效工具");

        BusinessException error = catchThrowableOfType(BusinessException.class,
                () -> controller.runResearch(request));

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("未启用");
        verify(client, never()).research(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("topic 为空：参数校验拦截")
    void rejectsBlankTopic() {
        ExternalAgentController.ResearchRunRequest request = new ExternalAgentController.ResearchRunRequest();
        request.setTopic("   ");

        BusinessException error = catchThrowableOfType(BusinessException.class,
                () -> controller.runResearch(request));

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("topic");
    }

    @Test
    @DisplayName("研究成功：返回选题与报告，并把报告回写知识库 + 记审计")
    void runsAndWritesBack() {
        when(client.research(eq("AI 提效工具"), eq("效率工具"), any(), eq(3), any()))
                .thenReturn(crewResult());
        when(ingestionPipeline.ingestText(any(), any(), any()))
                .thenReturn(new DocumentIngestionPipeline.IngestionResult(
                        "doc-1", 2, true, "ingested all 2 chunks", null));

        ExternalAgentController.ResearchRunRequest request = new ExternalAgentController.ResearchRunRequest();
        request.setTopic("AI 提效工具");
        request.setNiche("效率工具");
        request.setPlatforms(List.of("XHS", "WECHAT"));
        request.setMaxTopics(3);

        AgentResponse<Map<String, Object>> response = controller.runResearch(request);

        assertThat(response.isSuccess()).isTrue();
        Map<String, Object> data = response.getData();
        assertThat(data.get("executor")).isEqualTo("crewai");
        assertThat(data.get("reportMarkdown")).asString().contains("选题研究报告");
        assertThat((List<?>) data.get("topicCandidates")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> ingest = (Map<String, Object>) data.get("ingest");
        assertThat(ingest).containsEntry("success", true);
        verify(ingestionPipeline).ingestText(any(), any(), any());
        verify(auditService).record(eq("EXTERNAL_AGENT_RESEARCH"), eq("topic"), eq("AI 提效工具"), any());
    }

    @Test
    @DisplayName("关闭回写：只返回报告，不写知识库")
    void skipsWriteBackWhenDisabled() {
        properties.getCrewai().setWriteBackToKb(false);
        when(client.research(any(), any(), any(), any(), any())).thenReturn(crewResult());

        ExternalAgentController.ResearchRunRequest request = new ExternalAgentController.ResearchRunRequest();
        request.setTopic("AI 提效工具");

        AgentResponse<Map<String, Object>> response = controller.runResearch(request);

        assertThat(response.getData().get("ingest")).isNull();
        verify(ingestionPipeline, never()).ingestText(any(), any(), any());
    }

    @Test
    @DisplayName("crew 服务异常：转成业务异常，不把 500 抛给前端")
    void wrapsClientFailure() {
        when(client.research(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("connection refused"));

        ExternalAgentController.ResearchRunRequest request = new ExternalAgentController.ResearchRunRequest();
        request.setTopic("AI 提效工具");

        BusinessException error = catchThrowableOfType(BusinessException.class,
                () -> controller.runResearch(request));

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("CrewAI 研究失败");
    }

    @Test
    @DisplayName("状态接口：未启用时 crew 状态为 disabled")
    void statusWhenDisabled() {
        properties.getCrewai().setEnabled(false);

        AgentResponse<Map<String, Object>> response = controller.status();

        assertThat(response.getData()).containsEntry("crewaiEnabled", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> crew = (Map<String, Object>) response.getData().get("crew");
        assertThat(crew).containsEntry("status", "disabled");
    }
}
