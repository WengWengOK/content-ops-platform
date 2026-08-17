package com.contentops.common.mcp;

import com.contentops.common.platform.BilibiliPlatformService;
import com.contentops.common.platform.DouyinPlatformService;
import com.contentops.common.platform.ImageGenerationService;
import com.contentops.common.platform.KuaishouPlatformService;
import com.contentops.common.platform.WechatPlatformService;
import com.contentops.common.platform.XiaohongshuPlatformService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 真实平台 API 集成标记。
 *
 * <p>Spring @Component，将各平台 Service（{@link ImageGenerationService}、
 * {@link WechatPlatformService}、{@link DouyinPlatformService}、
 * {@link BilibiliPlatformService}、{@link KuaishouPlatformService}、
 * {@link XiaohongshuPlatformService}）的公共方法注册为 MCP 工具。
 *
 * <p>与 {@link McpToolScanner} 不同，平台 Service 的方法不带 @Tool 注解，
 * 因此不会被自动扫描。本组件在 {@code @PostConstruct} 阶段手动为每个平台
 * Service 的关键方法构建 {@link McpToolDescriptor} 并注册到
 * {@link McpToolRegistry}。
 *
 * <p><b>注册的工具来源</b>：
 * <ul>
 *   <li>DALL-E 3 图片生成 — {@link ImageGenerationService}</li>
 *   <li>微信公众号发布与数据分析 — {@link WechatPlatformService}</li>
 *   <li>抖音图文发布与视频数据 — {@link DouyinPlatformService}</li>
 *   <li>B站分区查询与视频投稿 — {@link BilibiliPlatformService}</li>
 *   <li>快手视频发布与数据查询 — {@link KuaishouPlatformService}</li>
 *   <li>小红书笔记数据与评论 — {@link XiaohongshuPlatformService}</li>
 * </ul>
 *
 * <p>通过 {@code contentops.mcp.enabled=true} 启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "contentops.mcp.enabled", havingValue = "true")
public class PlatformToolIntegration {

    private final McpToolRegistry mcpToolRegistry;

    private final ImageGenerationService imageGenerationService;
    private final WechatPlatformService wechatPlatformService;
    private final DouyinPlatformService douyinPlatformService;
    private final BilibiliPlatformService bilibiliPlatformService;
    private final KuaishouPlatformService kuaishouPlatformService;
    private final XiaohongshuPlatformService xiaohongshuPlatformService;

    /** 已注册的平台工具列表（用于 getPlatformTools 查询） */
    private final List<McpToolDescriptor> platformTools = new ArrayList<>();

    /**
     * 初始化：注册所有平台 Service 的方法为 MCP 工具。
     *
     * <p>在 Spring Bean 初始化完成后执行，此时所有平台 Service 和
     * McpToolRegistry 已就绪。
     */
    @PostConstruct
    public void init() {
        log.info("[Platform Integration] 开始注册平台 API 工具到 MCP 注册中心...");

        registerImageGenerationTools();
        registerWechatTools();
        registerDouyinTools();
        registerBilibiliTools();
        registerKuaishouTools();
        registerXiaohongshuTools();

        log.info("[Platform Integration] 平台 API 工具注册完成: 共 {} 个平台工具", platformTools.size());
    }

    // ════════════════════ 图片生成 (DALL-E 3) ════════════════════

    /**
     * 注册 DALL-E 3 图片生成工具。
     */
    private void registerImageGenerationTools() {
        try {
            // generateImage(String prompt, String size, String quality)
            Method method = ImageGenerationService.class.getMethod(
                    "generateImage", String.class, String.class, String.class);

            List<McpToolParameter> params = List.of(
                    McpToolParameter.builder()
                            .name("prompt").description("图片描述提示词（英文 Prompt 效果更佳）")
                            .type("String").required(true).build(),
                    McpToolParameter.builder()
                            .name("size").description("图片尺寸：1024x1024、1792x1024（横版）、1024x1792（竖版）")
                            .type("String").required(false).build(),
                    McpToolParameter.builder()
                            .name("quality").description("图片质量：standard 或 hd")
                            .type("String").required(false).build()
            );

            registerPlatformTool(imageGenerationService, method,
                    "DALL-E 3 图片生成：根据提示词生成真实图片，返回图片 URL", params);
        } catch (NoSuchMethodException e) {
            log.error("[Platform Integration] 注册图片生成工具失败", e);
        }
    }

    // ════════════════════ 微信公众号 ════════════════════

    /**
     * 注册微信公众号平台工具。
     */
    private void registerWechatTools() {
        try {
            // uploadPermanentMaterial(String imageUrl)
            registerPlatformTool(wechatPlatformService,
                    WechatPlatformService.class.getMethod("uploadPermanentMaterial", String.class),
                    "微信：上传永久图片素材，返回 media_id（用于图文封面）",
                    List.of(param("imageUrl", "图片 URL（如 DALL-E 生成的图片地址）", "String", true)));

            // publishArticleWithCover(String title, String htmlContent, String coverImageUrl, String digest, String author)
            registerPlatformTool(wechatPlatformService,
                    WechatPlatformService.class.getMethod("publishArticleWithCover",
                            String.class, String.class, String.class, String.class, String.class),
                    "微信：上传封面图并将图文添加到草稿箱（一站式发布入口）",
                    List.of(
                            param("title", "文章标题（最长 64 字符）", "String", true),
                            param("htmlContent", "文章 HTML 内容", "String", true),
                            param("coverImageUrl", "封面图 URL（可为空）", "String", false),
                            param("digest", "文章摘要（最长 120 字符）", "String", false),
                            param("author", "作者名", "String", false)));

            // addToDraft(String title, String content, String thumbMediaId, String digest, String author)
            registerPlatformTool(wechatPlatformService,
                    WechatPlatformService.class.getMethod("addToDraft",
                            String.class, String.class, String.class, String.class, String.class),
                    "微信：将图文添加到草稿箱",
                    List.of(
                            param("title", "文章标题", "String", true),
                            param("content", "文章 HTML 内容", "String", true),
                            param("thumbMediaId", "封面图 media_id（来自 uploadPermanentMaterial）", "String", false),
                            param("digest", "文章摘要", "String", false),
                            param("author", "作者名", "String", false)));

            // getArticleReadData(String date)
            registerPlatformTool(wechatPlatformService,
                    WechatPlatformService.class.getMethod("getArticleReadData", String.class),
                    "微信：获取指定日期的图文阅读数据（阅读人数、次数、分享数）",
                    List.of(param("date", "查询日期（yyyy-MM-dd，为空则查昨天）", "String", false)));

            // getUserSummary(String beginDate, String endDate)
            registerPlatformTool(wechatPlatformService,
                    WechatPlatformService.class.getMethod("getUserSummary", String.class, String.class),
                    "微信：获取用户汇总数据（新增、取消、净增粉丝，最大 7 天范围）",
                    List.of(
                            param("beginDate", "开始日期（yyyy-MM-dd）", "String", false),
                            param("endDate", "结束日期（yyyy-MM-dd）", "String", false)));

            // getArticleTotalDetail(String beginDate, String endDate)
            registerPlatformTool(wechatPlatformService,
                    WechatPlatformService.class.getMethod("getArticleTotalDetail", String.class, String.class),
                    "微信：获取图文详细数据（阅读完成率、平均阅读时长、评论收藏数）",
                    List.of(
                            param("beginDate", "开始日期（yyyy-MM-dd）", "String", false),
                            param("endDate", "结束日期（yyyy-MM-dd，此 API 最大 1 天范围）", "String", false)));

        } catch (NoSuchMethodException e) {
            log.error("[Platform Integration] 注册微信工具失败", e);
        }
    }

    // ════════════════════ 抖音 ════════════════════

    /**
     * 注册抖音平台工具。
     */
    private void registerDouyinTools() {
        try {
            // uploadImage(String accessToken, String openId, String imageUrl)
            registerPlatformTool(douyinPlatformService,
                    DouyinPlatformService.class.getMethod("uploadImage",
                            String.class, String.class, String.class),
                    "抖音：上传图片，返回 image_id（用于图文作品创建）",
                    List.of(
                            param("accessToken", "用户 OAuth access_token", "String", true),
                            param("openId", "用户 open_id", "String", true),
                            param("imageUrl", "图片 URL", "String", true)));

            // createImageText(String accessToken, String openId, String text, List<String> imageIds)
            registerPlatformTool(douyinPlatformService,
                    DouyinPlatformService.class.getMethod("createImageText",
                            String.class, String.class, String.class, List.class),
                    "抖音：创建图文作品（需要 video.create.bind 权限，限政务/媒体）",
                    List.of(
                            param("accessToken", "用户 OAuth access_token", "String", true),
                            param("openId", "用户 open_id", "String", true),
                            param("text", "图文文案（可含 #话题 和 @提及）", "String", true),
                            param("imageIds", "image_id 列表（来自 uploadImage）", "List", true)));

            // queryVideoList(String accessToken, String openId, int cursor, int count)
            registerPlatformTool(douyinPlatformService,
                    DouyinPlatformService.class.getMethod("queryVideoList",
                            String.class, String.class, int.class, int.class),
                    "抖音：查询用户已发布视频列表及统计数据",
                    List.of(
                            param("accessToken", "用户 OAuth access_token", "String", true),
                            param("openId", "用户 open_id", "String", true),
                            param("cursor", "分页游标（从 0 开始）", "int", false),
                            param("count", "每页数量（最大 20）", "int", false)));

        } catch (NoSuchMethodException e) {
            log.error("[Platform Integration] 注册抖音工具失败", e);
        }
    }

    // ════════════════════ B站 ════════════════════

    /**
     * 注册 B站平台工具。
     */
    private void registerBilibiliTools() {
        try {
            // queryCategoryList(String accessToken)
            registerPlatformTool(bilibiliPlatformService,
                    BilibiliPlatformService.class.getMethod("queryCategoryList", String.class),
                    "B站：查询视频分区列表（用于投稿时选择 typeId）",
                    List.of(param("accessToken", "OAuth access_token", "String", true)));

            // getVideoStats(String accessToken, String avid)
            registerPlatformTool(bilibiliPlatformService,
                    BilibiliPlatformService.class.getMethod("getVideoStats", String.class, String.class),
                    "B站：获取视频统计数据（播放、点赞、投币、收藏、分享）",
                    List.of(
                            param("accessToken", "OAuth access_token", "String", true),
                            param("avid", "视频 AV 号（如 av12345678）", "String", true)));

            // submitVideo(String accessToken, String title, String desc, int typeId, String tag, String coverUrl, String videoFilename)
            registerPlatformTool(bilibiliPlatformService,
                    BilibiliPlatformService.class.getMethod("submitVideo",
                            String.class, String.class, String.class, int.class,
                            String.class, String.class, String.class),
                    "B站：提交视频投稿（标题、描述、分区、标签、封面、视频文件名）",
                    List.of(
                            param("accessToken", "OAuth access_token", "String", true),
                            param("title", "视频标题", "String", true),
                            param("desc", "视频描述", "String", false),
                            param("typeId", "分区 ID（来自 queryCategoryList）", "int", true),
                            param("tag", "标签（逗号分隔）", "String", false),
                            param("coverUrl", "封面图 URL", "String", false),
                            param("videoFilename", "视频文件名", "String", true)));

            // uploadCover(String accessToken, String coverBase64)
            registerPlatformTool(bilibiliPlatformService,
                    BilibiliPlatformService.class.getMethod("uploadCover", String.class, String.class),
                    "B站：上传视频封面图（Base64 编码），返回封面 URL",
                    List.of(
                            param("accessToken", "OAuth access_token", "String", true),
                            param("coverBase64", "封面图 Base64 编码（不含 data:image 前缀）", "String", true)));

        } catch (NoSuchMethodException e) {
            log.error("[Platform Integration] 注册B站工具失败", e);
        }
    }

    // ════════════════════ 快手 ════════════════════

    /**
     * 注册快手平台工具。
     */
    private void registerKuaishouTools() {
        try {
            // publishVideo(String accessToken, String caption, String coverUrl)
            registerPlatformTool(kuaishouPlatformService,
                    KuaishouPlatformService.class.getMethod("publishVideo",
                            String.class, String.class, String.class),
                    "快手：发布视频（需要先完成 start_upload 和视频上传步骤）",
                    List.of(
                            param("accessToken", "OAuth access_token", "String", true),
                            param("caption", "视频文案（可含 #话题）", "String", true),
                            param("coverUrl", "封面图 URL", "String", false)));

            // getUserInfo(String accessToken)
            registerPlatformTool(kuaishouPlatformService,
                    KuaishouPlatformService.class.getMethod("getUserInfo", String.class),
                    "快手：获取用户信息（昵称、头像、粉丝数等）",
                    List.of(param("accessToken", "OAuth access_token", "String", true)));

            // queryVideoList(String accessToken, int page, int count)
            registerPlatformTool(kuaishouPlatformService,
                    KuaishouPlatformService.class.getMethod("queryVideoList",
                            String.class, int.class, int.class),
                    "快手：查询用户已发布视频列表",
                    List.of(
                            param("accessToken", "OAuth access_token", "String", true),
                            param("page", "页码（从 1 开始）", "int", false),
                            param("count", "每页数量", "int", false)));

            // queryVideoDetail(String accessToken, String photoId)
            registerPlatformTool(kuaishouPlatformService,
                    KuaishouPlatformService.class.getMethod("queryVideoDetail", String.class, String.class),
                    "快手：查询单个视频详情及统计数据",
                    List.of(
                            param("accessToken", "OAuth access_token", "String", true),
                            param("photoId", "视频 photo_id", "String", true)));

        } catch (NoSuchMethodException e) {
            log.error("[Platform Integration] 注册快手工具失败", e);
        }
    }

    // ════════════════════ 小红书 ════════════════════

    /**
     * 注册小红书平台工具。
     */
    private void registerXiaohongshuTools() {
        try {
            // getNoteDetail(String accessToken, String noteId)
            registerPlatformTool(xiaohongshuPlatformService,
                    XiaohongshuPlatformService.class.getMethod("getNoteDetail", String.class, String.class),
                    "小红书：获取笔记详情（含点赞、收藏、评论数等互动指标）",
                    List.of(
                            param("accessToken", "OAuth access_token", "String", true),
                            param("noteId", "笔记 ID", "String", true)));

            // getNoteComments(String accessToken, String noteId, String cursor, int limit)
            registerPlatformTool(xiaohongshuPlatformService,
                    XiaohongshuPlatformService.class.getMethod("getNoteComments",
                            String.class, String.class, String.class, int.class),
                    "小红书：获取笔记评论列表（用于情感分析）",
                    List.of(
                            param("accessToken", "OAuth access_token", "String", true),
                            param("noteId", "笔记 ID", "String", true),
                            param("cursor", "分页游标（首页传空）", "String", false),
                            param("limit", "每页数量", "int", false)));

        } catch (NoSuchMethodException e) {
            log.error("[Platform Integration] 注册小红书工具失败", e);
        }
    }

    // ════════════════════ 辅助方法 ════════════════════

    /**
     * 注册单个平台工具到 MCP 注册中心。
     *
     * <p>构建 {@link McpToolDescriptor}，注册到 {@link McpToolRegistry}，
     * 并记录工具调用日志。
     *
     * @param bean        平台 Service 实例
     * @param method      平台 Service 方法
     * @param description 工具描述
     * @param params      参数列表
     */
    private void registerPlatformTool(Object bean, Method method,
                                       String description, List<McpToolParameter> params) {
        String toolName = ClassUtils.getUserClass(bean).getSimpleName() + "." + method.getName();

        // 确保 method 可访问
        method.setAccessible(true);

        McpToolDescriptor descriptor = McpToolDescriptor.builder()
                .toolName(toolName)
                .description(description)
                .parameters(params)
                .returnType(method.getReturnType().getSimpleName())
                .bean(bean)
                .method(method)
                .build();

        mcpToolRegistry.register(descriptor);
        platformTools.add(descriptor);

        log.info("[Platform Integration] 平台工具已注册: {} | 描述: {} | 参数: {}",
                toolName, truncate(description, 60), params.size());
    }

    /**
     * 返回所有已注册的平台工具列表。
     *
     * @return 不可变的平台工具描述符列表
     */
    public List<McpToolDescriptor> getPlatformTools() {
        return Collections.unmodifiableList(platformTools);
    }

    /**
     * 快速构建参数描述符。
     */
    private McpToolParameter param(String name, String description, String type, boolean required) {
        return McpToolParameter.builder()
                .name(name)
                .description(description)
                .type(type)
                .required(required)
                .build();
    }

    /**
     * 截断字符串用于日志输出。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
