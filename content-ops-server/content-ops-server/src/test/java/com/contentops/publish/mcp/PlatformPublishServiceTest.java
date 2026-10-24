package com.contentops.publish.mcp;

import com.contentops.common.audit.AuditService;
import com.contentops.common.mcp.client.McpClient;
import com.contentops.common.mcp.client.McpClientException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.times;
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
        service = new PlatformPublishService(properties, factory, auditService, new ObjectMapper());
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
                "https://cdn/cover.jpg", "C:/works/demo.mp4", List.of("AI", "提效"), List.of(), null);
    }

    @Test
    @DisplayName("未配置 MCP：明确返回未真实上传（channel=simulated, ok=false）")
    void simulatedWhenNotConfigured() {
        when(factory.clientFor("douyin", "mcp")).thenReturn(Optional.empty());

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
        when(factory.clientFor("douyin", "mcp")).thenReturn(Optional.of(client));
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
        when(factory.clientFor("wechat", "mcp")).thenReturn(Optional.of(client));
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
        when(factory.clientFor("douyin", "mcp")).thenReturn(Optional.of(client));

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
        when(factory.clientFor("xiaohongshu", "mcp")).thenReturn(Optional.of(client));
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
        when(factory.clientFor("bilibili", "mcp")).thenReturn(Optional.of(client));
        when(client.listTools()).thenReturn(List.of(new McpClient.McpToolInfo("search_video", "搜索")));

        PlatformPublishService.PublishOutcome outcome = service.publish("bilibili", content(), false);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.detail()).contains("publish-tool").contains("search_video");
    }

    @Test
    @DisplayName("回归：绝不能把 create_directory / list_notes 这类工具猜成发布工具")
    void neverGuessesNonPublishTools() {
        properties.getPlatforms().put("mcp-reference", platform(null));
        when(factory.clientFor("mcp-reference", "mcp")).thenReturn(Optional.of(client));
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
        when(factory.clientFor("douyin", "mcp")).thenReturn(Optional.of(client));
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
        when(factory.clientFor("xiaohongshu", "mcp")).thenReturn(Optional.of(client));
        when(client.listTools()).thenThrow(new McpClientException("连接被拒绝"));

        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) service.status(true).get("platforms");
        @SuppressWarnings("unchecked")
        Map<String, Object> xhs = (Map<String, Object>) platforms.get("xiaohongshu");

        assertThat(xhs).containsEntry("probe", "failed");
        assertThat((String) xhs.get("probeError")).contains("连接被拒绝");
    }

    // ─────────────── 影刀 RPA 通道 ───────────────

    private void enableRpa(String platform, String publishTool, String statusTool) {
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform);
        PublishMcpProperties.Rpa rpa = new PublishMcpProperties.Rpa();
        rpa.setEnabled(true);
        rpa.setPublishTool(publishTool);
        rpa.setStatusTool(statusTool);
        rpa.setPollIntervalMs(10);
        rpa.setPollTimeoutMs(2000);
        cfg.setRpa(rpa);
    }

    @Test
    @DisplayName("auto 模式：原生 MCP 失败后回落影刀 RPA 并成功")
    void fallsBackToRpa() {
        properties.getPlatforms().put("douyin", platform("publish_video"));
        enableRpa("douyin", "runApp", null);
        McpClient rpaClient = mock(McpClient.class);
        when(factory.clientFor("douyin", "mcp")).thenReturn(Optional.of(client));
        when(factory.clientFor("douyin", "rpa")).thenReturn(Optional.of(rpaClient));
        when(client.listTools()).thenReturn(List.of(new McpClient.McpToolInfo("publish_video", "上传视频")));
        when(client.callTool(eq("publish_video"), any())).thenThrow(new McpClientException("浏览器未登录"));
        when(rpaClient.callTool(eq("runApp"), any()))
                .thenReturn(new McpClient.McpCallResult(true, "影刀应用已执行", null));

        PlatformPublishService.PublishOutcome outcome = service.publish("douyin", content(), false);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.channel()).isEqualTo("rpa");
        assertThat(outcome.tool()).isEqualTo("runApp");
        verify(rpaClient).callTool(eq("runApp"), any());
    }

    @Test
    @DisplayName("显式 channel=mcp：失败也不回落 RPA（避免误操作）")
    void explicitChannelDoesNotFallback() {
        properties.getPlatforms().put("douyin", platform("publish_video"));
        properties.getPlatforms().get("douyin").setChannel("mcp");
        enableRpa("douyin", "runApp", null);
        McpClient rpaClient = mock(McpClient.class);
        when(factory.clientFor("douyin", "mcp")).thenReturn(Optional.of(client));
        when(client.listTools()).thenReturn(List.of(new McpClient.McpToolInfo("publish_video", "上传视频")));
        when(client.callTool(eq("publish_video"), any())).thenThrow(new McpClientException("浏览器未登录"));

        PlatformPublishService.PublishOutcome outcome = service.publish("douyin", content(), false);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.channel()).isEqualTo("mcp");
        verify(rpaClient, never()).callTool(anyString(), any());
    }

    @Test
    @DisplayName("影刀 openApi 异步作业：startJob 拿 jobId，轮询 queryJob 到 SUCCESS")
    void pollsAsyncJob() {
        properties.getPlatforms().put("douyin", platform("publish_video"));
        properties.getPlatforms().get("douyin").setChannel("rpa");
        enableRpa("douyin", "startJob", "queryJob");
        properties.getPlatforms().get("douyin").getRpa().setJobIdField("jobId");
        McpClient rpaClient = mock(McpClient.class);
        when(factory.clientFor("douyin", "rpa")).thenReturn(Optional.of(rpaClient));
        when(rpaClient.callTool(eq("startJob"), any()))
                .thenReturn(new McpClient.McpCallResult(true, "{\"jobId\":\"job-42\"}",
                        "{\"content\":[{\"text\":\"{\\\"jobId\\\":\\\"job-42\\\"}\"}]}"));
        when(rpaClient.callTool(eq("queryJob"), any()))
                .thenReturn(new McpClient.McpCallResult(true, "{\"status\":\"RUNNING\"}", "{\"status\":\"RUNNING\"}"))
                .thenReturn(new McpClient.McpCallResult(true, "{\"status\":\"SUCCESS\"}", "{\"status\":\"SUCCESS\"}"));

        PlatformPublishService.PublishOutcome outcome = service.publish("douyin", content(), false);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.channel()).isEqualTo("rpa");
        assertThat(outcome.detail()).contains("job-42").contains("SUCCESS");
        verify(rpaClient, times(2)).callTool(eq("queryJob"), any());
    }

    @Test
    @DisplayName("影刀作业失败：ok=false，带失败状态")
    void reportsFailedJob() {
        properties.getPlatforms().put("douyin", platform("publish_video"));
        properties.getPlatforms().get("douyin").setChannel("rpa");
        enableRpa("douyin", "startJob", "queryJob");
        McpClient rpaClient = mock(McpClient.class);
        when(factory.clientFor("douyin", "rpa")).thenReturn(Optional.of(rpaClient));
        when(rpaClient.callTool(eq("startJob"), any()))
                .thenReturn(new McpClient.McpCallResult(true, "{\"jobId\":\"job-7\"}", "{\"jobId\":\"job-7\"}"));
        when(rpaClient.callTool(eq("queryJob"), any()))
                .thenReturn(new McpClient.McpCallResult(true,
                        "{\"status\":\"FAILED\",\"message\":\"账号未登录\"}",
                        "{\"status\":\"FAILED\",\"message\":\"账号未登录\"}"));

        PlatformPublishService.PublishOutcome outcome = service.publish("douyin", content(), false);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.detail()).contains("失败").contains("FAILED");
    }

    @Test
    @DisplayName("影刀未返回作业ID：明确报错，不假装成功")
    void missingJobId() {
        properties.getPlatforms().put("douyin", platform("publish_video"));
        properties.getPlatforms().get("douyin").setChannel("rpa");
        enableRpa("douyin", "startJob", "queryJob");
        McpClient rpaClient = mock(McpClient.class);
        when(factory.clientFor("douyin", "rpa")).thenReturn(Optional.of(rpaClient));
        when(rpaClient.callTool(eq("startJob"), any()))
                .thenReturn(new McpClient.McpCallResult(true, "已提交", null));

        PlatformPublishService.PublishOutcome outcome = service.publish("douyin", content(), false);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.detail()).contains("未从返回中解析到作业ID");
    }

    // ─────────────── 挂车与商品上架 ───────────────

    @Test
    @DisplayName("笔记发布带 products：挂车商品进入工具参数")
    void noteCarriesProducts() {
        properties.getPlatforms().put("xiaohongshu", platform("publish_content"));
        when(factory.clientFor("xiaohongshu", "mcp")).thenReturn(Optional.of(client));
        when(client.callTool(eq("publish_content"), any()))
                .thenReturn(new McpClient.McpCallResult(true, "已发布", null));
        PlatformPublishService.PublishContent withProducts = new PlatformPublishService.PublishContent(
                "标题", "正文", List.of("https://cdn/1.jpg"), null, null, null,
                List.of("保湿面膜", "sku-123"), null);

        PlatformPublishService.PublishOutcome outcome = service.publish("xiaohongshu", withProducts, false);

        assertThat(outcome.ok()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).callTool(eq("publish_content"), argsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<String> products = (List<String>) argsCaptor.getValue().get("products");
        assertThat(products).containsExactly("保湿面膜", "sku-123");
    }

    private PlatformPublishService.ProductContent product() {
        return new PlatformPublishService.ProductContent(
                "保湿面膜 10 片装", "59.90", 100, "补水保湿", List.of("https://cdn/p1.jpg"),
                "SKU-001", "美妆护肤");
    }

    @Test
    @DisplayName("商品上架校验：标题/图片/价格/库存")
    void validatesProduct() {
        properties.getPlatforms().put("stub", platform("publish_note"));
        properties.getPlatforms().get("stub").setProductTool("publish_product");

        assertThat(service.publishProduct("stub", new PlatformPublishService.ProductContent(
                " ", "1", 1, "", List.of("x"), null, null), false).detail())
                .contains("商品标题不能为空");
        assertThat(service.publishProduct("stub", new PlatformPublishService.ProductContent(
                "标题", "1", 1, "", List.of(), null, null), false).detail())
                .contains("商品图片不能为空");
        assertThat(service.publishProduct("stub", new PlatformPublishService.ProductContent(
                "标题", "abc", 1, "", List.of("x"), null, null), false).detail())
                .contains("商品价格格式不正确");
        assertThat(service.publishProduct("stub", new PlatformPublishService.ProductContent(
                "标题", "1", -1, "", List.of("x"), null, null), false).detail())
                .contains("商品库存不能为负");
    }

    @Test
    @DisplayName("商品上架：走配置的 product-tool，参数含价格/库存/图片")
    void publishesProductThroughMcp() {
        properties.getPlatforms().put("stub", platform("publish_note"));
        properties.getPlatforms().get("stub").setProductTool("publish_product");
        when(factory.clientFor("stub", "mcp")).thenReturn(Optional.of(client));
        when(client.callTool(eq("publish_product"), any()))
                .thenReturn(new McpClient.McpCallResult(true, "商品已上架，ID=stub-product-0001", null));

        PlatformPublishService.PublishOutcome outcome = service.publishProduct("stub", product(), false);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.channel()).isEqualTo("mcp");
        assertThat(outcome.tool()).isEqualTo("publish_product");
        assertThat(outcome.detail()).contains("stub-product-0001");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).callTool(eq("publish_product"), argsCaptor.capture());
        assertThat(argsCaptor.getValue())
                .containsEntry("title", "保湿面膜 10 片装")
                .containsEntry("price", "59.90")
                .containsEntry("stock", 100);
    }

    @Test
    @DisplayName("商品上架演练：只回显参数，不调工具")
    void productDryRun() {
        properties.getPlatforms().put("stub", platform("publish_note"));
        properties.getPlatforms().get("stub").setProductTool("publish_product");
        when(factory.clientFor("stub", "mcp")).thenReturn(Optional.of(client));

        PlatformPublishService.PublishOutcome outcome = service.publishProduct("stub", product(), true);

        assertThat(outcome.channel()).isEqualTo("mcp-dry-run");
        assertThat(outcome.detail()).contains("SKU-001").contains("59.90");
        verify(client, never()).callTool(anyString(), any());
    }
}
