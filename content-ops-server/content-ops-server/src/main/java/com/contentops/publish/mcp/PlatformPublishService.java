package com.contentops.publish.mcp;

import com.contentops.common.audit.AuditService;
import com.contentops.common.mcp.client.McpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                                 String videoPath, List<String> tags, List<String> products,
                                 String visibility) {
    }

    /** 商品上架内容（小红书千帆 / 影刀应用入参对齐这个结构） */
    public record ProductContent(String title, String price, Integer stock, String description,
                                 List<String> images, String sku, String category) {
    }

    /** 发布结果 */
    public record PublishOutcome(boolean ok, String channel, String tool, String detail,
                                 long elapsedMs, String raw) {
    }

    private final PublishMcpProperties properties;
    private final McpPublishClientFactory clientFactory;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

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
        List<String> channels = resolveChannels(key);
        if (channels.isEmpty()) {
            return new PublishOutcome(false, "simulated", null,
                    "平台 " + key + " 未配置 MCP 发布（或总开关关闭），未真实上传；"
                            + "请在 contentops.publish.mcp.platforms." + key + " 配置后再试",
                    System.currentTimeMillis() - started, null);
        }
        if (dryRun) {
            String channel = channels.get(0);
            Optional<McpClient> probeClient = clientFactory.clientFor(key, channel);
            String tool = "rpa".equals(channel)
                    ? rpaConfig(key).getPublishTool()
                    : (probeClient.isPresent() ? resolveToolName(key, probeClient.get()) : null);
            Map<String, Object> args = "rpa".equals(channel)
                    ? buildRpaArgs(key, content) : buildArgs(key, content);
            return new PublishOutcome(false, "mcp-dry-run", tool,
                    "演练模式未真实上传，将使用的参数：" + args,
                    System.currentTimeMillis() - started, null);
        }

        List<String> failures = new ArrayList<>();
        PublishOutcome last = null;
        boolean allowFallback = "auto".equalsIgnoreCase(configChannel(key));
        for (String channel : channels) {
            Optional<McpClient> client = clientFactory.clientFor(key, channel);
            if (client.isEmpty()) {
                failures.add(channel + "：未启用");
                continue;
            }
            try {
                PublishOutcome outcome = runOnChannel(key, channel, client.get(), content, started);
                if (outcome.ok()) {
                    audit(key, outcome);
                    return outcome;
                }
                failures.add(channel + "：" + outcome.detail());
                last = outcome;
                if (!allowFallback) {
                    audit(key, outcome);
                    return outcome;
                }
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - started;
                log.warn("[Publish] 平台 {} 的 {} 通道失败：{}", key, channel, e.getMessage());
                failures.add(channel + "：" + e.getMessage());
                last = new PublishOutcome(false, channel, null, "MCP 调用失败：" + e.getMessage(), elapsed, null);
                if (!allowFallback) {
                    audit(key, last);
                    return last;
                }
            }
        }
        PublishOutcome outcome = new PublishOutcome(false,
                last == null ? channels.get(channels.size() - 1) : last.channel(),
                last == null ? null : last.tool(),
                "所有发布通道都失败：" + String.join("；", failures),
                System.currentTimeMillis() - started, last == null ? null : last.raw());
        audit(key, outcome);
        return outcome;
    }

    /**
     * 商品上架（小红书千帆等）。
     *
     * <p>小红书没有开源的商品上架 MCP：优先走配置的 RPA 通道（影刀操作千帆商家后台），
     * 或任何提供 {@code product-tool} 的 MCP。参数与发布共用 {@code param-names}。
     */
    public PublishOutcome publishProduct(String platform, ProductContent product, boolean dryRun) {
        long started = System.currentTimeMillis();
        String key = platform == null ? "" : platform.trim().toLowerCase();
        List<String> channels = resolveChannels(key);
        if (channels.isEmpty()) {
            return new PublishOutcome(false, "simulated", null,
                    "平台 " + key + " 未配置发布通道，未真实上架商品", started, null);
        }
        String invalid = validateProduct(product);
        if (invalid != null) {
            return new PublishOutcome(false, "invalid", null, invalid, started, null);
        }
        if (dryRun) {
            String channel = channels.get(0);
            String tool = productTool(key, channel);
            return new PublishOutcome(false, "mcp-dry-run", tool,
                    "演练模式未真实上架，将使用的参数：" + buildProductArgs(key, channel, product),
                    System.currentTimeMillis() - started, null);
        }
        List<String> failures = new ArrayList<>();
        boolean allowFallback = "auto".equalsIgnoreCase(configChannel(key));
        for (String channel : channels) {
            Optional<McpClient> client = clientFactory.clientFor(key, channel);
            if (client.isEmpty()) {
                failures.add(channel + "：未启用");
                continue;
            }
            String tool = null;
            try {
                tool = productTool(key, channel);
                McpClient.McpCallResult result = client.get().callTool(tool, buildProductArgs(key, channel, product));
                if (result.ok()) {
                    PublishOutcome outcome;
                    if ("rpa".equals(channel) && hasStatusTool(key)) {
                        outcome = pollJob(key, tool, client.get(), result, started);
                    } else {
                        outcome = new PublishOutcome(true, channel, tool, summarise(result.text()),
                                System.currentTimeMillis() - started, result.raw());
                    }
                    if (outcome.ok()) {
                        audit(key, outcome);
                        return outcome;
                    }
                    failures.add(channel + "：" + outcome.detail());
                    if (!allowFallback) {
                        audit(key, outcome);
                        return outcome;
                    }
                } else {
                    failures.add(channel + "：工具返回错误：" + summarise(result.text()));
                }
            } catch (Exception e) {
                log.warn("[Publish] 平台 {} 的 {} 通道商品上架失败：{}", key, channel, e.getMessage());
                failures.add(channel + "：" + e.getMessage());
                if (!allowFallback) {
                    PublishOutcome outcome = new PublishOutcome(false, channel, tool,
                            "商品上架失败：" + e.getMessage(), System.currentTimeMillis() - started, null);
                    audit(key, outcome);
                    return outcome;
                }
            }
        }
        PublishOutcome outcome = new PublishOutcome(false, channels.get(channels.size() - 1), null,
                "所有通道商品上架都失败：" + String.join("；", failures),
                System.currentTimeMillis() - started, null);
        audit(key, outcome);
        return outcome;
    }

    private boolean hasStatusTool(String platform) {
        PublishMcpProperties.Rpa rpa = rpaConfig(platform);
        return rpa.getStatusTool() != null && !rpa.getStatusTool().isBlank();
    }

    /** 商品上架工具：渠道各自配置；没配就明确报错（不允许猜）。 */
    private String productTool(String platform, String channel) {
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform);
        String tool = "rpa".equals(channel) ? rpaConfig(platform).getProductTool()
                : (cfg == null ? null : cfg.getProductTool());
        if (tool == null || tool.isBlank()) {
            throw new IllegalStateException("平台 " + platform + " 的 " + channel
                    + " 通道未配置 product-tool（商品上架工具）");
        }
        return tool.trim();
    }

    private Map<String, Object> buildProductArgs(String platform, String channel, ProductContent product) {
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform);
        PublishMcpProperties.Rpa rpa = "rpa".equals(channel) ? rpaConfig(platform) : null;
        Map<String, String> names = rpa != null ? rpa.getParamNames() : cfg.getParamNames();
        Map<String, Object> args = new LinkedHashMap<>();
        putIfPresent(args, names.getOrDefault("title", "title"), product.title());
        putIfPresent(args, names.getOrDefault("price", "price"), product.price());
        putIfPresent(args, names.getOrDefault("stock", "stock"), product.stock());
        putIfPresent(args, names.getOrDefault("description", "description"), product.description());
        putIfPresent(args, names.getOrDefault("images", "images"), product.images());
        putIfPresent(args, names.getOrDefault("sku", "sku"), product.sku());
        putIfPresent(args, names.getOrDefault("category", "category"), product.category());
        Map<String, String> extras = rpa != null ? rpa.getExtraParams() : cfg.getExtraParams();
        if (extras != null) {
            extras.forEach((k, v) -> putIfPresent(args, names.getOrDefault(k, k), v));
        }
        return args;
    }

    private String validateProduct(ProductContent product) {
        if (product == null || product.title() == null || product.title().isBlank()) {
            return "商品标题不能为空";
        }
        if (product.images() == null || product.images().isEmpty()) {
            return "商品图片不能为空（至少一张主图）";
        }
        if (product.price() != null && !product.price().isBlank()) {
            try {
                if (new java.math.BigDecimal(product.price()).signum() < 0) {
                    return "商品价格不能为负：" + product.price();
                }
            } catch (NumberFormatException e) {
                return "商品价格格式不正确：" + product.price();
            }
        }
        if (product.stock() != null && product.stock() < 0) {
            return "商品库存不能为负：" + product.stock();
        }
        return null;
    }

    /** 单通道执行（RPA 通道在异步作业时会轮询状态）。 */
    private PublishOutcome runOnChannel(String platform, String channel, McpClient client,
                                        PublishContent content, long started) {
        boolean rpa = "rpa".equals(channel);
        String tool = rpa ? rpaConfig(platform).getPublishTool() : resolveToolName(platform, client);
        Map<String, Object> args = rpa ? buildRpaArgs(platform, content) : buildArgs(platform, content);
        McpClient.McpCallResult result = client.callTool(tool, args);
        long elapsed = System.currentTimeMillis() - started;
        if (!result.ok()) {
            return new PublishOutcome(false, channel, tool,
                    "工具返回错误：" + summarise(result.text()), elapsed, result.raw());
        }
        PublishMcpProperties.Rpa rpaCfg = rpa ? rpaConfig(platform) : null;
        if (rpaCfg != null && rpaCfg.getStatusTool() != null && !rpaCfg.getStatusTool().isBlank()) {
            return pollJob(platform, tool, client, result, started);
        }
        log.info("[Publish] 平台 {} 经 {} 发布成功：tool={}, elapsedMs={}", platform, channel, tool, elapsed);
        return new PublishOutcome(true, channel, tool, summarise(result.text()), elapsed, result.raw());
    }

    /** 影刀开放 API 的 startJob 是异步的：拿 jobId 轮询 queryJob，直到成功/失败/超时。 */
    private PublishOutcome pollJob(String platform, String publishTool, McpClient client,
                                   McpClient.McpCallResult startResult, long started) {
        PublishMcpProperties.Rpa cfg = rpaConfig(platform);
        String jobIdField = cfg.getJobIdField() == null || cfg.getJobIdField().isBlank()
                ? "jobId" : cfg.getJobIdField();
        String jobId = extractField(startResult, jobIdField);
        if (jobId == null || jobId.isBlank()) {
            return new PublishOutcome(false, "rpa", publishTool,
                    "启动成功但未从返回中解析到作业ID（字段 " + jobIdField + "）：" + summarise(startResult.text()),
                    System.currentTimeMillis() - started, startResult.raw());
        }
        long deadline = System.currentTimeMillis() + Math.max(1000, cfg.getPollTimeoutMs());
        String lastStatus = "";
        while (System.currentTimeMillis() < deadline) {
            McpClient.McpCallResult statusResult = client.callTool(cfg.getStatusTool(),
                    Map.of(jobIdField, jobId));
            lastStatus = summarise(statusResult.text());
            String statusValue = extractField(statusResult, cfg.getJobStatusField());
            String haystack = (statusValue == null ? "" : statusValue) + " " + lastStatus;
            if (containsAny(haystack, cfg.getJobDoneValues())) {
                return new PublishOutcome(true, "rpa", publishTool,
                        "作业 " + jobId + " 完成：" + lastStatus,
                        System.currentTimeMillis() - started, statusResult.raw());
            }
            if (containsAny(haystack, cfg.getJobFailedValues())) {
                return new PublishOutcome(false, "rpa", publishTool,
                        "作业 " + jobId + " 失败：" + lastStatus,
                        System.currentTimeMillis() - started, statusResult.raw());
            }
            try {
                Thread.sleep(Math.max(200, cfg.getPollIntervalMs()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new PublishOutcome(false, "rpa", publishTool,
                        "轮询被中断：" + lastStatus, System.currentTimeMillis() - started, null);
            }
        }
        return new PublishOutcome(false, "rpa", publishTool,
                "作业 " + jobId + " 轮询超时（" + cfg.getPollTimeoutMs() + "ms），最后状态：" + lastStatus,
                System.currentTimeMillis() - started, null);
    }

    /** 从工具返回里取字段：先按原始 JSON 找，再按文本 JSON 找（工具常把 JSON 塞在 content.text 里）。 */
    private String extractField(McpClient.McpCallResult result, String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        for (String candidate : new String[]{result.raw(), result.text()}) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(candidate);
                JsonNode found = findField(node, field, 0);
                if (found != null && !found.isNull()) {
                    return found.asText();
                }
            } catch (Exception ignored) {
                // 不是 JSON，继续尝试下一个来源
            }
        }
        return null;
    }

    private JsonNode findField(JsonNode node, String field, int depth) {
        if (node == null || depth > 4) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(field);
            if (direct != null) {
                return direct;
            }
            java.util.Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                JsonNode found = findField(children.next(), field, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findField(child, field, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isTextual()) {
            String text = node.asText();
            if (text.stripLeading().startsWith("{")) {
                try {
                    return findField(objectMapper.readTree(text), field, depth + 1);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean containsAny(String text, String csvValues) {
        if (text == null || csvValues == null) {
            return false;
        }
        for (String value : csvValues.split("[,，]")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && text.toUpperCase().contains(trimmed.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    private void audit(String platform, PublishOutcome outcome) {
        auditService.record("PLATFORM_PUBLISH", "platform", platform,
                "channel=" + outcome.channel() + "，tool=" + outcome.tool()
                        + "，ok=" + outcome.ok() + "，耗时 " + outcome.elapsedMs() + "ms");
    }

    /** 通道顺序：auto → 原生 MCP 优先、失败回落 RPA；也可显式指定 mcp / rpa。 */
    private List<String> resolveChannels(String platform) {
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform);
        if (cfg == null) {
            return List.of();
        }
        boolean mcp = cfg.isEnabled();
        boolean rpa = cfg.getRpa() != null && cfg.getRpa().isEnabled();
        return switch (configChannel(platform)) {
            case "mcp" -> mcp ? List.of("mcp") : List.of();
            case "rpa" -> rpa ? List.of("rpa") : List.of();
            default -> {
                List<String> channels = new ArrayList<>();
                if (mcp) {
                    channels.add("mcp");
                }
                if (rpa) {
                    channels.add("rpa");
                }
                yield channels;
            }
        };
    }

    private String configChannel(String platform) {
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform);
        String channel = cfg == null || cfg.getChannel() == null ? "auto" : cfg.getChannel().trim().toLowerCase();
        return channel.isEmpty() ? "auto" : channel;
    }

    private PublishMcpProperties.Rpa rpaConfig(String platform) {
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform);
        if (cfg == null || cfg.getRpa() == null) {
            throw new IllegalStateException("平台 " + platform + " 未配置 RPA 通道");
        }
        return cfg.getRpa();
    }

    /** 直接调某个工具（排查/调试用，比如先 list_accounts 看登录态）。 */
    public McpClient.McpCallResult callTool(String platform, String tool, Map<String, Object> args) {
        return callTool(platform, "mcp", tool, args);
    }

    /** 按通道调工具（mcp / rpa），例如 rpa 通道先调 queryApplist 摸清应用与参数。 */
    public McpClient.McpCallResult callTool(String platform, String channel, String tool,
                                            Map<String, Object> args) {
        McpClient client = clientFactory.clientFor(platform, channel)
                .orElseThrow(() -> new IllegalStateException(
                        "平台 " + platform + " 的 " + channel + " 通道未启用"));
        return client.callTool(tool, args == null ? Map.of() : args);
    }

    /** 列出该平台 MCP 暴露的工具（探活）。 */
    public List<McpClient.McpToolInfo> listTools(String platform) {
        return listTools(platform, "mcp");
    }

    /** 按通道列出工具（mcp / rpa）。 */
    public List<McpClient.McpToolInfo> listTools(String platform, String channel) {
        McpClient client = clientFactory.clientFor(platform, channel)
                .orElseThrow(() -> new IllegalStateException(
                        "平台 " + platform + " 的 " + channel + " 通道未启用"));
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
            info.put("channel", cfg.getChannel());
            info.put("target", "stdio".equalsIgnoreCase(cfg.getTransport())
                    ? String.join(" ", cfg.getCommand()) : cfg.getUrl());
            info.put("publishTool", cfg.getPublishTool());
            info.put("note", cfg.getNote());
            PublishMcpProperties.Rpa rpa = cfg.getRpa();
            if (rpa != null && rpa.isEnabled()) {
                Map<String, Object> rpaInfo = new LinkedHashMap<>();
                rpaInfo.put("enabled", true);
                rpaInfo.put("target", String.join(" ", rpa.getCommand()));
                rpaInfo.put("publishTool", rpa.getPublishTool());
                rpaInfo.put("statusTool", rpa.getStatusTool());
                rpaInfo.put("async", rpa.getStatusTool() != null && !rpa.getStatusTool().isBlank());
                info.put("rpa", rpaInfo);
                if (probe) {
                    try {
                        List<McpClient.McpToolInfo> rpaTools = listTools(key, "rpa");
                        rpaInfo.put("probe", "ok");
                        rpaInfo.put("toolCount", rpaTools.size());
                        rpaInfo.put("tools", rpaTools.stream().limit(20)
                                .map(McpClient.McpToolInfo::name).toList());
                    } catch (Exception e) {
                        rpaInfo.put("probe", "failed");
                        rpaInfo.put("probeError", e.getMessage());
                    }
                }
            }
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
        putIfPresent(args, names.getOrDefault("products", "products"), content.products());
        putIfPresent(args, names.getOrDefault("visibility", "visibility"), content.visibility());
        if (cfg != null) {
            cfg.getExtraParams().forEach((k, v) -> putIfPresent(args, names.getOrDefault(k, k), v));
        }
        return args;
    }

    /**
     * RPA 通道的参数映射：影刀应用的入参名由应用自己定义，
     * 先用 {@code queryRobotParam} 查清楚，再在 {@code rpa.param-names} 里对齐。
     */
    private Map<String, Object> buildRpaArgs(String platform, PublishContent content) {
        PublishMcpProperties.Rpa cfg = rpaConfig(platform);
        Map<String, String> names = cfg.getParamNames();
        Map<String, Object> args = new LinkedHashMap<>();
        putIfPresent(args, names.getOrDefault("title", "title"), content.title());
        putIfPresent(args, names.getOrDefault("content", "content"), content.content());
        putIfPresent(args, names.getOrDefault("images", "images"), content.images());
        putIfPresent(args, names.getOrDefault("cover", "cover"), content.coverUrl());
        putIfPresent(args, names.getOrDefault("video", "video"), content.videoPath());
        putIfPresent(args, names.getOrDefault("tags", "tags"), content.tags());
        putIfPresent(args, names.getOrDefault("products", "products"), content.products());
        putIfPresent(args, names.getOrDefault("visibility", "visibility"), content.visibility());
        cfg.getExtraParams().forEach((k, v) -> putIfPresent(args, names.getOrDefault(k, k), v));
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
