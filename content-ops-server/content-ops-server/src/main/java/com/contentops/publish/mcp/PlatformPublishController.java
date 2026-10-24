package com.contentops.publish.mcp;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.mcp.client.McpClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台发布（MCP）接口。
 *
 * <p>用社区现成的平台 MCP server 把作品传上去（抖音/小红书/公众号…），
 * 平台差异通过配置隔离；未配置时明确返回"未真实上传"，不伪造成功。
 */
@RestController
@RequestMapping("/api/v1/publish/platforms")
@RequiredArgsConstructor
@Tag(name = "平台发布（MCP）")
public class PlatformPublishController {

    private final PlatformPublishService publishService;
    private final McpPublishClientFactory clientFactory;

    @GetMapping("/status")
    @Operation(summary = "各平台 MCP 发布配置与探活（probe=true 时按平台拉取 tools/list）")
    public AgentResponse<Map<String, Object>> status(
            @RequestParam(required = false, defaultValue = "false") boolean probe) {
        return AgentResponse.success("publish", publishService.status(probe));
    }

    @GetMapping("/fields")
    @Operation(summary = "发布内容的规范字段（配置 param-names 时按它改名）")
    public AgentResponse<List<String>> fields() {
        return AgentResponse.success("publish", PlatformPublishService.canonicalFields());
    }

    @PostMapping("/{platform}/publish")
    @Operation(summary = "把作品发布到指定平台（优先走 MCP；未配置则返回未真实上传）")
    public AgentResponse<Map<String, Object>> publish(@PathVariable String platform,
                                                      @RequestBody PublishRequest request) {
        PlatformPublishService.PublishContent content = new PlatformPublishService.PublishContent(
                request.getTitle(), request.getContent(), request.getImages(), request.getCoverUrl(),
                request.getVideoPath(), request.getTags(), request.getProducts(), request.getVisibility());
        PlatformPublishService.PublishOutcome outcome =
                publishService.publish(platform, content, request.isDryRun());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("platform", platform);
        data.put("ok", outcome.ok());
        data.put("channel", outcome.channel());
        data.put("tool", outcome.tool());
        data.put("detail", outcome.detail());
        data.put("elapsedMs", outcome.elapsedMs());
        return AgentResponse.success("publish", data);
    }

    @PostMapping("/{platform}/products")
    @Operation(summary = "商品上架（小红书千帆走影刀 RPA 或支持 product-tool 的 MCP）")
    public AgentResponse<Map<String, Object>> publishProduct(@PathVariable String platform,
                                                             @RequestBody ProductRequest request) {
        PlatformPublishService.ProductContent product = new PlatformPublishService.ProductContent(
                request.getTitle(), request.getPrice(), request.getStock(), request.getDescription(),
                request.getImages(), request.getSku(), request.getCategory());
        PlatformPublishService.PublishOutcome outcome =
                publishService.publishProduct(platform, product, request.isDryRun());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("platform", platform);
        data.put("ok", outcome.ok());
        data.put("channel", outcome.channel());
        data.put("tool", outcome.tool());
        data.put("detail", outcome.detail());
        data.put("elapsedMs", outcome.elapsedMs());
        return AgentResponse.success("publish", data);
    }

    @PostMapping("/{platform}/tools/{tool}/call")
    @Operation(summary = "直接调用某个 MCP 工具（排查登录态等，如 list_accounts）")
    public AgentResponse<Map<String, Object>> callTool(@PathVariable String platform,
                                                       @PathVariable String tool,
                                                       @RequestBody(required = false) Map<String, Object> args) {
        McpClient.McpCallResult result = publishService.callTool(platform, tool, args);
        return toolResult(result);
    }

    @PostMapping("/{platform}/channels/{channel}/tools/{tool}/call")
    @Operation(summary = "按通道调工具：mcp（平台原生）/ rpa（影刀，如 queryApplist、queryRobotParam）")
    public AgentResponse<Map<String, Object>> callChannelTool(@PathVariable String platform,
                                                              @PathVariable String channel,
                                                              @PathVariable String tool,
                                                              @RequestBody(required = false) Map<String, Object> args) {
        McpClient.McpCallResult result = publishService.callTool(platform, channel, tool, args);
        return toolResult(result);
    }

    private AgentResponse<Map<String, Object>> toolResult(McpClient.McpCallResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", result.ok());
        data.put("text", result.text());
        return AgentResponse.success("publish", data);
    }

    @PostMapping("/{platform}/reset")
    @Operation(summary = "重建该平台的 MCP 客户端（改完配置或进程挂掉后调用）")
    public AgentResponse<Map<String, Object>> reset(@PathVariable String platform) {
        boolean configured = publishService.mcpConfigured(platform);
        clientFactory.reset(platform);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("platform", platform);
        data.put("mcpEnabled", configured);
        data.put("reset", true);
        return AgentResponse.success("publish", data);
    }

    @Data
    public static class PublishRequest {
        private String title;
        private String content;
        private List<String> images;
        private String coverUrl;
        private String videoPath;
        private List<String> tags;
        /** 挂车商品关键词或商品 ID（小红书 xiaohongshu-mcp 的 publish_content.products） */
        private List<String> products;
        /** 可见范围：公开可见（默认）/ 仅自己可见 / 仅互关好友可见（平台支持时生效） */
        private String visibility;
        private boolean dryRun;
    }

    @Data
    public static class ProductRequest {
        private String title;
        /** 价格，字符串避免浮点误差（如 "59.90"） */
        private String price;
        private Integer stock;
        private String description;
        private List<String> images;
        private String sku;
        private String category;
        private boolean dryRun;
    }
}
