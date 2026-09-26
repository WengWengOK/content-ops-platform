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
     * <b>不含笔记评论接口</b>。因此真实评论数据需来自以下任一渠道，配置后即可直接切换，无需改代码：
     * <ol>
     *   <li><b>第三方数据服务 / 自建采集桥</b>（推荐）：配置 {@code endpoint} + {@code access-token}，
     *       鉴权 {@code bearer}（默认）或 {@code query}；</li>
     *   <li><b>官方开放平台网关</b>（如已获批对应接口权限）：配置 {@code endpoint} +
     *       {@code app-id}/{@code app-secret}，鉴权 {@code ark-sign}（appId+timestamp+secret 签名）。</li>
     * </ol>
     */
    @Data
    public static class XiaohongshuProperties {

        /** 是否启用真实小红书评论接口 */
        private boolean enabled = false;

        /** 接口基址（endpoint 为相对路径时使用） */
        private String baseUrl = "https://open.xiaohongshu.com";

        /** 评论列表接口：完整 URL 或相对 base-url 的路径，例如 /api/v1/note/comment/list */
        private String endpoint = "";

        /** HTTP 方法：POST | GET */
        private String method = "POST";

        /** 鉴权方式：bearer | query | ark-sign | none */
        private String authMode = "bearer";

        /** 静态访问令牌（第三方数据服务 / 自建采集桥 / 官方授权令牌） */
        private String accessToken = "";

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
    }
}