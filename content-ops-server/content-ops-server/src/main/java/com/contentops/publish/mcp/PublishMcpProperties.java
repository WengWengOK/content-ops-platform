package com.contentops.publish.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台发布 MCP 配置（{@code contentops.publish.mcp.*}）。
 *
 * <p>每个平台一个 MCP server（社区现成实现），支持两种传输：
 * <ul>
 *   <li>{@code stdio} —— 本地进程（npx/node/python），如抖音创作者平台自动化上传、文颜本地模式；</li>
 *   <li>{@code http} —— Streamable HTTP 地址，如小红书 MCP（HTTP + MCP 双模式）、文颜远程模式。</li>
 * </ul>
 *
 * <p>默认全部关闭：MCP server 通常要登录态（浏览器 profile / Cookie），
 * 没配好就拉起进程只会失败，所以按平台显式开启。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.publish.mcp")
public class PublishMcpProperties {

    /** MCP 发布总开关（关掉后全部平台走模拟发布） */
    private boolean enabled = true;

    /** 握手超时（stdio 首次启动可能很慢，默认 60s） */
    private int initializeTimeoutMs = 60_000;

    /** 平台 → MCP server 配置 */
    private Map<String, PlatformMcp> platforms = new LinkedHashMap<>();

    /** 单个平台的 MCP server 配置 */
    @Data
    public static class PlatformMcp {

        /** 是否启用该平台的 MCP 发布 */
        private boolean enabled = false;

        /** 传输：http | stdio */
        private String transport = "http";

        /** HTTP 传输：MCP 服务地址 */
        private String url;

        /** HTTP 传输：Bearer Token（不带 Bearer 前缀） */
        private String token;

        /** stdio 传输：启动命令（如 npx -y douyin-upload-mcp） */
        private List<String> command = new ArrayList<>();

        /** stdio 传输：环境变量（如 CDP 调试端口、账号配置路径） */
        private Map<String, String> env = new LinkedHashMap<>();

        /** stdio 传输：工作目录 */
        private String workDir;

        /** 单次调用超时（上传大文件要留足） */
        private int timeoutMs = 180_000;

        /** 发布工具名；留空则按名称启发式从 tools/list 里挑（publish/upload/post/create） */
        private String publishTool;

        /** 参数名映射：我们的规范字段 → 该 MCP 工具的参数名 */
        private Map<String, String> paramNames = new LinkedHashMap<>();

        /** 固定附加参数（如平台标识、可见性） */
        private Map<String, String> extraParams = new LinkedHashMap<>();

        /** 备注（说明这个 MCP server 的来源与登录前置条件） */
        private String note;
    }
}
