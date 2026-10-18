package com.contentops.publish.mcp;

import com.contentops.common.audit.AuditService;
import com.contentops.common.mcp.client.McpClient;
import com.contentops.common.mcp.client.McpClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台发布链路测试：MCP 优先、参数映射、演练模式、失败不伪装成功、状态探活。
 */
class PlatformPublishServiceTest {

    private PublishMcpProperties properties;
    private McpPublishClientFactory factory;
    private AuditService auditService;
    private McpClient client;
    private PlatformPublishService service;

    @BeforeEach
    void setUp() {
        properties = new PublishMcpProperties();
        factory = mock(McpPublishClientFactory.class);
        auditService = mock(AuditService.class);
        client = mock(McpClient.class);
        service = new PlatformPublishService(properties, factory, auditService);
    }

    private PublishMcpProperties.PlatformMcp platform(String publishTool) {
        PublishMcpProperties.PlatformMcp cfg = new PublishMcpProperties.PlatformMcp();
        cfg.setEnabled(true);
        cfg.setTransport("stdio");
        cfg.setPublishTool(publishTool);
        cfg.getParamNames().put("content", "desc");
        cfg.getParamNames().put("video", "video_path");
        cfg.getExtraParams().put("visibility", "public");
        return cfg;
    }

    private PlatformPublishService.PublishContent content() {
        return new PlatformPublishService.PublishContent(
                "AI 提效实测", "正文内容", List.of("https://cdn/1.jpg"),
                "https://cdn/cover.jpg", "C:/works/demo.mp4", List.of("AI", "提效"));
    }

    @Test
    @DisplayName("未配置 MCP：明确返回未真实上传（channel=simulated, ok=false）")
    void simulatedWhenNotConfigured() {
        when(factory.clientFor("douyin")).thenReturn(Optional.empty());

        PlatformPublishService.PublishOutcome outcome = service.publish("douyin", content(), false);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.channel()).isEqualTo("simulated");
        assertThat(outcome.detail()).contains("未配置 MCP").contains("未真实上传");
        verify(client, never()).callTool(anyString(), any());
    }

    @Test
    @DisplayName("走 MCP：自动挑发布工具 + 按 param-names 映射参数 + 记审计")
    void publishesThroughMcp() {
        properties.getPlatforms().put("douyin", platform(null));
        when(factory.clientFor("douyin")).thenReturn(Optional.of(client));
        when(client.listTools()).thenReturn(List.of(
                new McpClient.McpToolInfo("check_login", "检查登录态"),
                new McpClient.McpToolInfo("publish_video", "上传视频")));
        when(client.callTool(eq("publish_video"), any()))
                .thenReturn(new McpClient.McpCallResult(true, "已提交上传", "{\"ok\":true}"));

        PlatformPublishService.PublishOutcome outcome = service.publish("douyin", content(), false);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.channel()).isEqualTo("mcp");
        assertThat(outcome.tool()).isEqualTo("publish_video");
        assertThat(outcome.detail()).isEqualTo("已提交上传");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).callTool(eq("publish_video"), argsCaptor.capture());
        assertThat(argsCaptor.getValue())
                .containsEntry("title", "AI 提效实测")
                .containsEntry("desc", "正文内容")            // content → desc
                .containsEntry("video_path", "C:/works/demo.mp4") // video → video_path
                .containsEntry("visibility", "public");      // extra-params
        verify(auditService).record(eq("PLATFORM_PUBLISH"), eq("platform"), eq("douyin"), anyString());
    }

    @Test
    @DisplayName("显式配置 publish-tool 时不再拉 tools/list")
    void respectsConfiguredTool() {
        properties.getPlatforms().put("wechat", platform("publish_article"));
        when(factory.clientFor("wechat")).thenReturn(Optional.of(client));
        when(client.callTool(eq("publish_article"), any()))
                .thenReturn(new McpClient.McpCallResult(true, "已发布", null));

        PlatformPublishService.PublishOutcome outcome = service.publish("wechat", content(), false);

        assertThat(outcome.tool()).isEqualTo("publish_article");
        verify(client, never()).listTools();
    }

    @Test
    @DisplayName("演练模式：只回显将使用的参数，不调用工具")
    void dryRun() {
        properties.getPlatforms().put("douyin", platform("publish_video"));
        when(factory.clientFor("douyin")).thenReturn(Optional.of(client));

        PlatformPublishService.PublishOutcome outcome = service.publish("douyin", content(), true);

        assertThat(outcome.channel()).isEqualTo("mcp-dry-run");
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.detail()).contains("desc").contains("C:/works/demo.mp4");
        verify(client, never()).callTool(anyString(), any());
    }

    @Test
    @DisplayName("MCP 调用失败：ok=false，原因透出并记审计，不伪装成功")
    void mcpFailureIsReported() {
        properties.getPlatforms().put("xiaohongshu", platform("publish_note"));
        when(factory.clientFor("xiaohongshu")).thenReturn(Optional.of(client));
        when(client.callTool(eq("publish_note"), any()))
                .thenThrow(new McpClientException("未登录小红书，请先扫码"));

        PlatformPublishService.PublishOutcome outcome = service.publish("xiaohongshu", content(), false);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.channel()).isEqualTo("mcp");
        assertThat(outcome.detail()).contains("未登录小红书");
        verify(auditService).record(eq("PLATFORM_PUBLISH"), eq("platform"), eq("xiaohongshu"), anyString());
    }

    @Test
    @DisplayName("MCP 没有发布类工具：给出可执行的提示（要求显式配置 publish-tool）")
    void noPublishToolExposed() {
        properties.getPlatforms().put("bilibili", platform(null));
        when(factory.clientFor("bilibili")).thenReturn(Optional.of(client));
        when(client.listTools()).thenReturn(List.of(new McpClient.McpToolInfo("search_video", "搜索")));

        PlatformPublishService.PublishOutcome outcome = service.publish("bilibili", content(), false);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.detail()).contains("publish-tool").contains("search_video");
    }

    @Test
    @DisplayName("回归：绝不能把 create_directory / list_notes 这类工具猜成发布工具")
    void neverGuessesNonPublishTools() {
        properties.getPlatforms().put("mcp-reference", platform(null));
        when(factory.clientFor("mcp-reference")).thenReturn(Optional.of(client));
        when(client.listTools()).thenReturn(List.of(
                new McpClient.McpToolInfo("read_file", "读文件"),
                new McpClient.McpToolInfo("create_directory", "建目录"),
                new McpClient.McpToolInfo("list_notes", "列出笔记"),
                new McpClient.McpToolInfo("edit_file", "编辑文件")));

        PlatformPublishService.PublishOutcome outcome = service.publish("mcp-reference", content(), false);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.detail()).contains("publish-tool").contains("create_directory");
        verify(client, never()).callTool(anyString(), any());
    }

    @Test
    @DisplayName("状态总览：列出平台配置；probe=true 时探活，失败降级为 probe=failed")
    void statusAndProbe() {
        properties.getPlatforms().put("douyin", platform("publish_video"));
        properties.getPlatforms().put("wechat", new PublishMcpProperties.PlatformMcp());
        when(factory.clientFor("douyin")).thenReturn(Optional.of(client));
        when(client.listTools()).thenReturn(List.of(new McpClient.McpToolInfo("publish_video", "上传视频")));

        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) service.status(true).get("platforms");
        @SuppressWarnings("unchecked")
        Map<String, Object> douyin = (Map<String, Object>) platforms.get("douyin");
        @SuppressWarnings("unchecked")
        Map<String, Object> wechat = (Map<String, Object>) platforms.get("wechat");

        assertThat(douyin).containsEntry("probe", "ok").containsEntry("toolCount", 1);
        assertThat(wechat).containsEntry("enabled", false);
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) douyin.get("tools");
        assertThat(tools).containsExactly("publish_video");
    }

    @Test
    @DisplayName("探活失败不影响状态接口")
    void probeFailureIsGraceful() {
        properties.getPlatforms().put("xiaohongshu", platform("publish_note"));
        when(factory.clientFor("xiaohongshu")).thenReturn(Optional.of(client));
        when(client.listTools()).thenThrow(new McpClientException("连接被拒绝"));

        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) service.status(true).get("platforms");
        @SuppressWarnings("unchecked")
        Map<String, Object> xhs = (Map<String, Object>) platforms.get("xiaohongshu");

        assertThat(xhs).containsEntry("probe", "failed");
        assertThat((String) xhs.get("probeError")).contains("连接被拒绝");
    }
}
