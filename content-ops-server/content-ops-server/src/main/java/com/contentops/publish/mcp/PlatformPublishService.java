package com.contentops.publish.mcp;

import com.contentops.common.audit.AuditService;
import com.contentops.common.mcp.client.McpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 平台发布服务：**优先走各平台现成的 MCP server** 把作品传上去，没配置则给出明确的"未上传"结果。
 *
 * <p>为什么走 MCP 而不是自己重新实现一遍上传：
 * <ol>
 *   <li>平台（抖音/小红书/公众号）上传协议经常变，社区 MCP server 有人持续维护；</li>
 *   <li>多数平台上传要登录态（浏览器 profile / Cookie），MCP server 已经把这块做完了；</li>
 *   <li>我们只管把「作品内容」映射成工具参数，平台差异被隔离在配置里（{@code param-names}）。</li>
 * </ol>
 *
 * <p>失败绝不假装成功：未配置 → {@code channel=simulated} 且 {@code ok=false}；
 * MCP 调用异常 → {@code channel=mcp} 且 {@code ok=false}，原因写在 detail 里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformPublishService {

    /**
     * 发布语义词：**只认这些强语义**，绝不靠 "create"/"note"/"video" 这类弱词猜。
     *
     * <p>实测教训：接官方 filesystem MCP 时，弱词 "create" 让 {@code create_directory} 被当成发布工具，
     * 直接带着发布参数去调文件系统工具 —— 猜错方向比不猜危险得多，识别不出来就要求显式配置。
     */
    private static final List<String> PUBLISH_HINTS = List.of(
            "publish", "upload", "post_note", "post_video", "post_article",
            "send_note", "send_video", "发布", "上传");
    /** 查询类工具前缀：命中即排除，避免把 search_video / list_notes 当成发布工具 */
    private static final List<String> QUERY_PREFIXES = List.of(
            "search", "list", "get", "query", "check", "fetch", "login", "read", "find", "status");

    /** 规范化字段 → 值 */
    public record PublishContent(String title, String content, List<String> images, String coverUrl,
                                 String videoPath, List<String> tags) {
    }

    /** 发布结果 */
    public record PublishOutcome(boolean ok, String channel, String tool, String detail,
                                 long elapsedMs, String raw) {
    }

    private final PublishMcpProperties properties;
    private final McpPublishClientFactory clientFactory;
    private final AuditService auditService;

    /** 该平台是否已配置 MCP（供前端提示"当前是模拟还是真实上传"）。 */
    public boolean mcpConfigured(String platform) {
        return clientFactory.enabledFor(platform);
    }

    /**
     * 发布作品到指定平台。
     *
     * @param platform 平台代号（douyin/xiaohongshu/wechat/bilibili/kuaishou）
     * @param content  作品内容（标题/正文/图片/封面/视频路径/标签）
     * @param dryRun   只演练参数不真实上传
     */
    public PublishOutcome publish(String platform, PublishContent content, boolean dryRun) {
        long started = System.currentTimeMillis();
        String key = platform == null ? "" : platform.trim().toLowerCase();
        Optional<McpClient> client = clientFactory.clientFor(key);
        if (client.isEmpty()) {
            return new PublishOutcome(false, "simulated", null,
                    "平台 " + key + " 未配置 MCP 发布（或总开关关闭），未真实上传；"
                            + "请在 contentops.publish.mcp.platforms." + key + " 配置后再试",
                    System.currentTimeMillis() - started, null);
        }
        if (dryRun) {
            Map<String, Object> args = buildArgs(key, content);
            return new PublishOutcome(false, "mcp-dry-run", resolveToolName(key, client.get()),
                    "演练模式未真实上传，将使用的参数：" + args,
                    System.currentTimeMillis() - started, null);
        }
        String tool = null;
        try {
            tool = resolveToolName(key, client.get());
            Map<String, Object> args = buildArgs(key, content);
            McpClient.McpCallResult result = client.get().callTool(tool, args);
            long elapsed = System.currentTimeMillis() - started;
            auditService.record("PLATFORM_PUBLISH", "platform", key,
                    "MCP 发布：tool=" + tool + "，ok=" + result.ok() + "，耗时 " + elapsed + "ms");
            log.info("[Publish] 平台 {} MCP 发布完成：tool={}, ok={}, elapsedMs={}",
                    key, tool, result.ok(), elapsed);
            return new PublishOutcome(result.ok(), "mcp", tool,
                    summarise(result.text()), elapsed, result.raw());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            log.warn("[Publish] 平台 {} MCP 发布失败：{}", key, e.getMessage());
            auditService.record("PLATFORM_PUBLISH", "platform", key,
                    "MCP 发布失败：tool=" + tool + "，原因=" + e.getMessage());
            return new PublishOutcome(false, "mcp", tool, "MCP 调用失败：" + e.getMessage(), elapsed, null);
        }
    }

    /** 直接调某个工具（排查/调试用，比如先 list_accounts 看登录态）。 */
    public McpClient.McpCallResult callTool(String platform, String tool, Map<String, Object> args) {
        McpClient client = clientFactory.clientFor(platform)
                .orElseThrow(() -> new IllegalStateException("平台 " + platform + " 未启用 MCP"));
        return client.callTool(tool, args == null ? Map.of() : args);
    }

    /** 列出该平台 MCP 暴露的工具（探活）。 */
    public List<McpClient.McpToolInfo> listTools(String platform) {
        McpClient client = clientFactory.clientFor(platform)
                .orElseThrow(() -> new IllegalStateException("平台 " + platform + " 未启用 MCP"));
        return client.listTools();
    }

    /** 状态总览：每个平台的配置与（可选）探活结果。 */
    public Map<String, Object> status(boolean probe) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("platforms", platformStatus(probe));
        return result;
    }

    private Map<String, Object> platformStatus(boolean probe) {
        Map<String, Object> platforms = new LinkedHashMap<>();
        for (Map.Entry<String, PublishMcpProperties.PlatformMcp> entry : properties.getPlatforms().entrySet()) {
            String key = entry.getKey();
            PublishMcpProperties.PlatformMcp cfg = entry.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("enabled", cfg.isEnabled());
            info.put("transport", cfg.getTransport());
            info.put("target", "stdio".equalsIgnoreCase(cfg.getTransport())
                    ? String.join(" ", cfg.getCommand()) : cfg.getUrl());
            info.put("publishTool", cfg.getPublishTool());
            info.put("note", cfg.getNote());
            if (probe && cfg.isEnabled()) {
                try {
                    List<McpClient.McpToolInfo> tools = listTools(key);
                    info.put("probe", "ok");
                    info.put("toolCount", tools.size());
                    info.put("tools", tools.stream().limit(20).map(McpClient.McpToolInfo::name).toList());
                } catch (Exception e) {
                    info.put("probe", "failed");
                    info.put("probeError", e.getMessage());
                }
            }
            platforms.put(key, info);
        }
        return platforms;
    }

    // ─────────────── 工具选择与参数映射 ───────────────

    /** 发布工具：优先用配置的；没配就从 tools/list 里按关键词挑。 */
    private String resolveToolName(String platform, McpClient client) {
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform);
        if (cfg != null && cfg.getPublishTool() != null && !cfg.getPublishTool().isBlank()) {
            return cfg.getPublishTool().trim();
        }
        List<McpClient.McpToolInfo> tools = client.listTools();
        for (McpClient.McpToolInfo tool : tools) {
            String name = tool.name() == null ? "" : tool.name().toLowerCase();
            if (isQueryTool(name)) {
                continue;
            }
            for (String hint : PUBLISH_HINTS) {
                if (name.contains(hint)) {
                    return tool.name();
                }
            }
        }
        throw new IllegalStateException("平台 " + platform + " 的 MCP 没有名字像发布类的工具，"
                + "请在 contentops.publish.mcp.platforms." + platform + ".publish-tool 里显式指定；"
                + "当前可用工具：" + tools.stream().map(McpClient.McpToolInfo::name).toList());
    }

    private boolean isQueryTool(String name) {
        for (String prefix : QUERY_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把作品内容映射成工具参数：
     * 规范字段（title/content/images/cover/video/tags）按 {@code param-names} 改名，
     * 空值不下发，最后合并 {@code extra-params}。
     */
    private Map<String, Object> buildArgs(String platform, PublishContent content) {
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform);
        Map<String, String> names = cfg == null ? Map.of() : cfg.getParamNames();
        Map<String, Object> args = new LinkedHashMap<>();
        putIfPresent(args, names.getOrDefault("title", "title"), content.title());
        putIfPresent(args, names.getOrDefault("content", "content"), content.content());
        putIfPresent(args, names.getOrDefault("images", "images"), content.images());
        putIfPresent(args, names.getOrDefault("cover", "cover"), content.coverUrl());
        putIfPresent(args, names.getOrDefault("video", "video"), content.videoPath());
        putIfPresent(args, names.getOrDefault("tags", "tags"), content.tags());
        if (cfg != null) {
            cfg.getExtraParams().forEach((k, v) -> putIfPresent(args, names.getOrDefault(k, k), v));
        }
        return args;
    }

    private void putIfPresent(Map<String, Object> args, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return;
        }
        args.put(key, value);
    }

    private String summarise(String text) {
        if (text == null || text.isBlank()) {
            return "MCP 工具已执行，无文本返回";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 500 ? flat.substring(0, 500) + "…" : flat;
    }

    /** 供控制器展示的规范字段说明。 */
    public static List<String> canonicalFields() {
        return new ArrayList<>(List.of("title", "content", "images", "cover", "video", "tags"));
    }
}
