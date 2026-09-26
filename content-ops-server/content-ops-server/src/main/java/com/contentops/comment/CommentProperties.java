package com.contentops.comment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评论区 AI 助手配置（{@code contentops.comment.*}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.comment")
public class CommentProperties {

    /** 是否启用评论区 AI 助手 */
    private boolean enabled = true;

    /** 评论定时采集间隔（毫秒），默认 10 分钟 */
    private long collectMs = 600_000;

    /** 单次模拟采集条数 */
    private int mockCount = 10;

    /**
     * 采集数据源：{@code auto}（优先真实 API，不可用时回退）| {@code api}（仅真实 API）| {@code mock}（仅模拟）。
     */
    private String source = "auto";

    /** 真实数据源不可用时是否回退到模拟数据（保证开发环境全链路可跑） */
    private boolean fallbackToMock = true;

    /** 单轮定时任务最多采集的作品数（防止一次拉太多触发平台风控） */
    private int maxWorksPerTick = 20;

    /** 新采集到的评论是否自动做 AI 意图/情感分析 */
    private boolean autoAnalyze = true;

    /** 单轮自动分析条数上限（成本控制） */
    private int autoAnalyzeLimit = 20;

    /** 是否启用定时采集（关闭后仅支持手动触发） */
    private boolean scheduled = true;

    /** 小红书评论数据源配置 */
    private XiaohongshuProperties xiaohongshu = new XiaohongshuProperties();


    /** 是否仅使用模拟数据 */
    public boolean mockOnly() {
        return "mock".equalsIgnoreCase(source == null ? "" : source.trim());
    }

    /**
     * 小红书评论接口配置。
     *
     * <p><b>关于数据来源：</b>小红书官方开放平台（open.xiaohongshu.com）当前仅开放电商类 API
     * （公共/订单/售后/商品/库存/素材中心/物流/财务/即时零售/会员通/供货商），
     * <b>不含笔记评论接口</b>。因此真实评论数据需来自以下渠道，配置后即可直接切换，无需改代码：
     * <ol>
     *   <li><b>自建采集桥 xiaohongshu-mcp</b>（推荐，用自己账号登录后拉评论区数据）：
     *       设 {@code preset=xhs-mcp}，再配 {@code base-url=http://127.0.0.1:18060} 与 {@code access-token}，
     *       接口为 {@code POST /api/v1/feeds/detail}，需要 {@code feed_id + xsec_token}；</li>
     *   <li><b>第三方数据服务</b>：配置 {@code endpoint} + {@code access-token}，鉴权 {@code bearer} 或 {@code query}；</li>
     *   <li><b>官方开放平台网关</b>（如已获批接口权限）：配置 {@code endpoint} +
     *       {@code app-id}/{@code app-secret}，鉴权 {@code ark-sign}（appId+timestamp+secret 签名）。</li>
     * </ol>
     */
    @Data
    public static class XiaohongshuProperties {

        /** 是否启用真实小红书评论接口 */
        private boolean enabled = false;

        /**
         * 预设数据源：{@code custom}（自定义端点，默认）| {@code xhs-mcp}
         * （自建桥 xiaohongshu-mcp：自动使用 POST /api/v1/feeds/detail + feed_id/xsec_token 请求体）。
         */
        private String preset = "custom";

        /** 接口基址（endpoint 为相对路径时使用） */
        private String baseUrl = "https://open.xiaohongshu.com";

        /** 评论列表接口：完整 URL 或相对 base-url 的路径，例如 /api/v1/note/comment/list */
        private String endpoint = "";

        /** HTTP 方法：POST | GET */
        private String method = "POST";

        /** 鉴权方式：bearer | query | ark-sign | none */
        private String authMode = "bearer";

        /** 静态访问令牌（自建桥的 Bearer Token / 第三方数据服务令牌 / 官方授权令牌） */
        private String accessToken = "";

        /**
         * 默认 xsec_token（小红书笔记的访问票据，形如 {@code AB...}）。
         * 单篇笔记一个 token 且会过期，推荐在「监控作品」里逐个填写。
         */
        private String xsecToken = "";

        /** xsec_token 在请求体中的字段名 */
        private String xsecTokenParam = "xsec_token";

        /** 开放平台 AppID（ark-sign 模式必填） */
        private String appId = "";

        /** 开放平台 AppSecret（ark-sign 模式必填） */
        private String appSecret = "";

        /** 网关方法名（ark-sign 模式部分网关必填，如 note.comment.list） */
        private String methodName = "note.comment.list";

        /** 请求超时（毫秒） */
        private int timeoutMs = 20_000;

        /** 单页评论条数（多数平台上限 20） */
        private int pageSize = 20;

        /** 单次采集最多翻页数 */
        private int maxPages = 5;

        /** 作品 ID 在请求中的字段名 */
        private String noteIdParam = "note_id";

        /** 分页游标字段名 */
        private String cursorParam = "cursor";

        /** 分页大小字段名 */
        private String pageSizeParam = "limit";

        /** 评论列表在响应 JSON 中的路径（为空时自动探测 data.list 等常见路径） */
        private String listPath = "";

        /** 同一作品两次采集之间的最小间隔（秒），防止高频调用触发风控 */
        private int minIntervalSeconds = 60;

        /**
         * 是否允许「真实发送回复」（审核通过后调用平台接口回复评论）。
         * 关闭或未配置接口时，发送仅记录状态（模拟发送），不会真的回复到平台。
         */
        private boolean replyEnabled = true;

        /** 回复接口路径（自建桥默认 /api/v1/feeds/comment/reply） */
        private String replyPath = "";

        /** 回复请求体中的评论 ID 字段名 */
        private String commentIdParam = "comment_id";

        /** 回复请求体中的内容字段名 */
        private String contentParam = "content";
    }
}