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

        /** 商品上架工具名（该平台的 MCP 若支持商品创建/上架则填，如 publish_product） */
        private String productTool;

        /** 参数名映射：我们的规范字段 → 该 MCP 工具的参数名 */
        private Map<String, String> paramNames = new LinkedHashMap<>();

        /** 固定附加参数（如平台标识、可见性） */
        private Map<String, String> extraParams = new LinkedHashMap<>();

        /** 备注（说明这个 MCP server 的来源与登录前置条件） */
        private String note;

        /**
         * 发布通道：{@code auto}（MCP → RPA → 模拟）/ {@code mcp} / {@code rpa}。
         * RPA 通道用于 MCP 不可用或需要真实 UI 操作的场景（如影刀）。
         */
        private String channel = "auto";

        /** 影刀 RPA 通道（官方 MCP Server：npx -y yingdao-mcp-server） */
        private Rpa rpa = new Rpa();
    }

    /**
     * RPA 通道配置（影刀官方 MCP Server 暴露的工具）。
     *
     * <p>本地模式工具：{@code queryApplist} / {@code queryRobotParam} / {@code runApp}；
     * 开放 API 模式额外有 {@code uploadFile} / {@code startJob} / {@code queryJob} / {@code queryClientList}。
     */
    @Data
    public static class Rpa {

        /** 是否启用 RPA 通道 */
        private boolean enabled = false;

        /** 启动命令（默认影刀官方 MCP Server） */
        private List<String> command = new ArrayList<>(List.of("npx", "-y", "yingdao-mcp-server"));

        /**
         * 环境变量：
         * local → RPA_MODEL=local, SHADOWBOT_PATH, USER_FOLDER；
         * openApi → RPA_MODEL=openApi, ACCESS_KEY_ID, ACCESS_KEY_SECRET。
         */
        private Map<String, String> env = new LinkedHashMap<>();

        private String workDir;

        /** 握手/工具调用超时 */
        private int timeoutMs = 180_000;

        /** 发布工具：local 用 runApp；openApi 用 startJob（异步） */
        private String publishTool = "runApp";

        /** 商品上架工具：影刀里通常也是 runApp（用另一个 appId / 参数区分），按应用实际工具名填 */
        private String productTool = "runApp";

        /** 异步作业状态查询工具（openApi 的 startJob 配套 queryJob；local 留空） */
        private String statusTool;

        /** 从 startJob 返回里取作业 ID 的字段名（留空则用整段返回文本） */
        private String jobIdField;

        /** 判定作业完成的字段名（如 status） */
        private String jobStatusField = "status";

        /** 作业完成的状态值（逗号分隔） */
        private String jobDoneValues = "SUCCESS,SUCCEEDED,SUCCESSFUL,COMPLETED,DONE,FINISHED";

        /** 作业失败的状态值（逗号分隔） */
        private String jobFailedValues = "FAILED,FAIL,ERROR,CANCELLED,CANCELED";

        /** 轮询间隔 */
        private int pollIntervalMs = 3000;

        /** 轮询最长等待 */
        private int pollTimeoutMs = 600000;

        /** 我们的规范字段 → 影刀应用参数名（先用 queryRobotParam 查清楚再填） */
        private Map<String, String> paramNames = new LinkedHashMap<>();

        /** 固定参数（如应用 ID appId、机器人组、平台标识） */
        private Map<String, String> extraParams = new LinkedHashMap<>();

        private String note;
    }
}
