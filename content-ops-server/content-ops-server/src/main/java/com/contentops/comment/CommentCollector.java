package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 评论采集器：统一入口，按配置选择「真实接口」或「模拟数据」。
 *
 * <p>数据源策略（{@code contentops.comment.source}）：
 * <ul>
 *   <li>{@code auto}（默认）：真实接口已配置则走真实接口，否则/调用失败时回退模拟数据；</li>
 *   <li>{@code api}：只走真实接口，失败直接抛错（生产环境推荐，避免把模拟数据当真实数据）；</li>
 *   <li>{@code mock}：只走模拟数据（开发/演示）。</li>
 * </ul>
 *
 * <p>是否允许回退由 {@code contentops.comment.fallback-to-mock} 控制，返回结果里的
 * {@code source} 与 {@code fallbackReason} 会一并落库/返回前端，保证「数据来源可追溯」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentCollector {

    private final CommentProperties properties;
    private final XhsCommentApiClient apiClient;

    /** 采集结果：评论 + 数据源标记 + 回退原因。 */
    public record CollectionResult(String platform, String workId, String source,
                                   String fallbackReason, List<Comment> comments) {

        public List<Comment> commentsOrEmpty() {
            return comments == null ? List.of() : comments;
        }

        public boolean fromApi() {
            return "api".equalsIgnoreCase(source);
        }
    }

    /** 采集指定作品的小红书评论（默认平台）。 */
    public CollectionResult collect(String workId, String ownerId) {
        return collect("xiaohongshu", workId, ownerId, null);
    }



    /**
     * 采集指定平台作品的评论。
     *
     * @param platform 平台编码（当前真实接口仅支持 xiaohongshu，其余平台回退模拟）
     * @param workId   作品/笔记 ID
     * @param ownerId  归属用户（租户隔离，可为 null 表示开发模式）
     */
    public CollectionResult collect(String platform, String workId, String ownerId) {
        return collect(platform, workId, ownerId, null);
    }

    /** 采集指定平台作品评论（可带 xsec_token）。 */
    public CollectionResult collect(String platform, String workId, String ownerId, String xsecToken) {
        String p = platform == null || platform.isBlank() ? "xiaohongshu" : platform.trim();
        if (workId == null || workId.isBlank()) {
            throw new CommentSourceException("COMMENT_WORK_ID_REQUIRED", "workId 不能为空");
        }
        if (properties.mockOnly()) {
            return mock(p, workId, ownerId, "数据源配置为 mock（contentops.comment.source=mock）");
        }

        String source = properties.getSource() == null ? "auto" : properties.getSource().trim().toLowerCase();
        boolean apiConfigured = apiClient.isConfigured();

        // source=api：只走真实接口，未配置或调用失败都直接报错，避免把模拟数据当成真实数据
        if ("api".equals(source)) {
            if (!apiConfigured) {
                throw new CommentSourceException("COMMENT_SOURCE_NOT_CONFIGURED", apiClient.statusHint());
            }
            List<Comment> comments = fetchFromApi(p, workId, ownerId, xsecToken);
            log.info("[Comment] 真实接口采集完成: platform={}, workId={}, 条数={}", p, workId, comments.size());
            return new CollectionResult(p, workId, "api", null, comments);
        }

        // source=auto：真实接口优先；未配置或失败时按 fallback-to-mock 决定是否回退模拟数据
        if (!apiConfigured) {
            String reason = apiClient.statusHint();
            if (!properties.isFallbackToMock()) {
                throw new CommentSourceException("COMMENT_SOURCE_NOT_CONFIGURED", reason);
            }
            return mock(p, workId, ownerId, reason);
        }

        try {
            List<Comment> comments = fetchFromApi(p, workId, ownerId, xsecToken);
            log.info("[Comment] 真实接口采集完成: platform={}, workId={}, 条数={}", p, workId, comments.size());
            return new CollectionResult(p, workId, "api", null, comments);
        } catch (CommentSourceException e) {
            if (!properties.isFallbackToMock()) {
                throw e;
            }
            log.warn("[Comment] 真实接口采集失败，回退模拟数据: code={}, msg={}", e.getCode(), e.getMessage());
            CollectionResult fallback = mock(p, workId, ownerId, e.getCode() + ": " + e.getMessage());
            return new CollectionResult(fallback.platform(), fallback.workId(), "mock",
                    fallback.fallbackReason(), fallback.comments());
        }
    }
    /** 真实接口分页拉取。 */
    private List<Comment> fetchFromApi(String platform, String workId, String ownerId, String xsecToken) {
        if (!"xiaohongshu".equalsIgnoreCase(platform)) {
            throw new CommentSourceException("COMMENT_PLATFORM_NOT_SUPPORTED",
                    "暂未接入 " + platform + " 的真实评论接口，可配置 contentops.comment.source=mock 使用模拟数据");
        }
        CommentProperties.XiaohongshuProperties cfg = properties.getXiaohongshu();
        List<Comment> all = new ArrayList<>();
        String cursor = "";
        LocalDateTime now = LocalDateTime.now();
        int maxPages = Math.max(1, cfg.getMaxPages());
        for (int page = 0; page < maxPages; page++) {
            XhsCommentApiClient.FetchPage fetched =
                    apiClient.fetchPage(workId, cursor, cfg.getPageSize(), xsecToken);
            for (XhsCommentApiClient.XhsComment item : fetched.comments()) {
                all.add(Comment.builder()
                        .commentId(resolveCommentId(workId, item))
                        .platformCommentId(item.commentId())
                        .ownerId(ownerId)
                        .platform("xiaohongshu")
                        .workId(workId)
                        .author(item.author() == null ? "匿名用户" : item.author())
                        .content(item.content())
                        .likes(item.likes())
                        .commentTime(item.commentTime() == null ? now : item.commentTime())
                        .replyTo(item.replyTo())
                        .replyStatus("NONE")
                        .collectedAt(now)
                        .build());
            }
            if (!fetched.hasMore() || fetched.nextCursor() == null || fetched.nextCursor().isBlank()) {
                break;
            }
            cursor = fetched.nextCursor();
        }
        return all;
    }

    /** 平台未返回评论 ID 时，用内容指纹生成稳定 ID，保证重复采集不重复入库。 */
    private String resolveCommentId(String workId, XhsCommentApiClient.XhsComment item) {
        if (item.commentId() != null && !item.commentId().isBlank()) {
            return "xhs-" + item.commentId().trim();
        }
        String raw = workId + "|" + safe(item.author()) + "|" + safe(item.content()) + "|"
                + (item.commentTime() == null ? "" : item.commentTime().toString());
        return "xhs-" + DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    /** 小红书评论语料池（模拟数据源，按种子抽取保证同一作品多次采集结果稳定）。 */
    private static final String[][] MOCK_POOL = {
            {"种草小鹿", "蹲一个详细教程，真的太需要了！", "咨询"},
            {"爱吃火锅的喵", "这个配色好好看，求链接求链接~", "潜在客户"},
            {"程序媛小林", "姐妹这个是怎么做的呀？可以出个图文版吗", "咨询"},
            {"熬夜冠军", "已收藏，坐等更新，别鸽哦！", "表扬"},
            {"职场小透明", "感觉内容有点浅，能不能再深入讲讲原理", "反馈"},
            {"奶茶三分糖", "请问用的什么相机和滤镜？质感好好", "咨询"},
            {"路人甲乙丙", "路过，广告？", "无关"},
            {"理性消费者", "价格有点贵啊，有平替吗？", "售后"},
            {"资深运营", "选题不错，但开头不够抓人，建议改一版", "反馈"},
            {"退堂鼓选手", "上次按你说的做了，效果一般，怎么回事？", "售后"},
            {"柠檬精本精", "呵呵，又是恰饭内容", "吐槽"},
            {"早八打工人", "蹲一个 PDF 版，方便打印", "潜在客户"},
            {"美妆观察员", "这套方法论很实用，已转发给同事", "表扬"},
            {"吃瓜不嫌事大", "评论区吵起来了？我搬个小板凳", "无关"},
            {"新手上路", "第一次看你的内容，讲得好清楚，关注了！", "表扬"},
            {"省钱小能手", "有没有优惠券或者活动价？想入手", "潜在客户"},
            {"键盘侠本侠", "就这？我上我也行", "吐槽"},
            {"认真做笔记", "第二章的案例能展开说说吗？想看", "咨询"},
            {"数据爱好者", "有数据支撑吗？感觉结论有点武断", "反馈"},
            {"同款老粉", "从去年追到现在，每期都看，加油！", "表扬"},
            {"隐私保护者", "这个 App 会不会泄露隐私？有点担心", "售后"},
            {"周末去哪儿", "博主坐标哪里？想线下交流", "咨询"},
            {"标题党杀手", "标题和内容不符啊，差评", "吐槽"},
            {"行动派", "已经下单啦，到货来反馈", "潜在客户"},
    };

    /** 模拟采集：按 workId 种子抽取若干条评论（含少量楼中楼回复）。 */
    private CollectionResult mock(String platform, String workId, String ownerId, String reason) {
        Random random = new Random(workId == null ? 42L : workId.hashCode() * 31L + 7L);
        int count = properties.getMockCount() > 0 ? properties.getMockCount() : 8 + random.nextInt(5);
        count = Math.min(count, MOCK_POOL.length);

        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < MOCK_POOL.length; i++) {
            indexes.add(i);
        }
        for (int i = indexes.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = indexes.get(i);
            indexes.set(i, indexes.get(j));
            indexes.set(j, tmp);
        }

        List<Comment> comments = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < count; i++) {
            String[] row = MOCK_POOL[indexes.get(i)];
            comments.add(Comment.builder()
                    .commentId("xhs-mock-" + (workId == null ? "none" : workId) + "-" + (i + 1))
                    .ownerId(ownerId)
                    .platform(platform)
                    .workId(workId)
                    .author(row[0])
                    .content(row[1])
                    .likes(random.nextInt(300))
                    .commentTime(now.minusMinutes(random.nextInt(60 * 24 * 3)))
                    .replyStatus("NONE")
                    .collectedAt(now)
                    .build());
        }
        if (comments.size() >= 3) {
            int threadIdx = random.nextInt(comments.size());
            Comment parent = comments.get(threadIdx);
            comments.add(Comment.builder()
                    .commentId("xhs-mock-" + (workId == null ? "none" : workId) + "-thread-" + (threadIdx + 1))
                    .ownerId(ownerId)
                    .platform(platform)
                    .workId(workId)
                    .author("楼主")
                    .content("谢谢支持！已私信你啦～")
                    .likes(random.nextInt(50))
                    .commentTime(now.minusMinutes(random.nextInt(120)))
                    .replyTo(parent.getCommentId())
                    .replyStatus("NONE")
                    .collectedAt(now)
                    .build());
        }
        log.info("[Comment] 模拟采集完成: project={}, workId={}, 条数={}, 原因={}",
                platform, workId, comments.size(), reason);
        return new CollectionResult(platform, workId, "mock", reason, comments);
    }

    /** 兼容旧调用：仅返回模拟数据。 */
    public List<Comment> collectMockComments(String workId, String ownerId) {
        return mock("xiaohongshu", workId, ownerId, "手动调用模拟采集").comments();
    }

    /** 兼容旧调用：返回采集结果里的评论列表（内部按配置选择数据源）。 */
    public List<Comment> collectFromPlatform(String workId, String ownerId) {
        return collect(workId, ownerId).commentsOrEmpty();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}