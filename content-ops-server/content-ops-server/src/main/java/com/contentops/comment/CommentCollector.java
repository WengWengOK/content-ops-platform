package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 小红书评论采集器（MVP：模拟数据源）。
 *
 * <p>当前阶段以 {@link #collectMockComments} 提供真实感评论数据用于跑通
 * 「采集 → 分析 → 意图识别 → AI 对话 → 审核回复」全链路；
 * 真实平台采集接口预留 {@link #collectFromPlatform}，后续接入小红书开放平台
 * 或爬虫采集（注意合规风控）时替换实现即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentCollector {

    private final CommentProperties properties;

    /** 小红书评论语料池（按种子抽取，保证同一作品多次采集结果稳定） */
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

    /**
     * 模拟采集：按 workId 种子抽取 8-12 条评论（含少量楼中楼回复）。
     */
    public List<Comment> collectMockComments(String workId, String ownerId) {
        Random random = new Random(workId == null ? 42L : workId.hashCode() * 31L + 7L);
        int count = properties.getMockCount() > 0
                ? properties.getMockCount()
                : 8 + random.nextInt(5);
        count = Math.min(count, MOCK_POOL.length);

        // 打乱语料池下标
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
            String commentId = "xhs-mock-" + (workId == null ? "none" : workId) + "-" + (i + 1);
            comments.add(Comment.builder()
                    .commentId(commentId)
                    .ownerId(ownerId)
                    .platform("xiaohongshu")
                    .workId(workId)
                    .author(row[0])
                    .content(row[1])
                    .likes(random.nextInt(300))
                    .commentTime(now.minusMinutes(random.nextInt(60 * 24 * 3)))
                    .replyStatus("NONE")
                    .collectedAt(now)
                    .build());
        }

        // 给部分评论追加楼中楼（replyTo 指向一条已有评论）
        if (comments.size() >= 3) {
            int threadIdx = random.nextInt(comments.size());
            Comment parent = comments.get(threadIdx);
            comments.add(Comment.builder()
                    .commentId("xhs-mock-" + (workId == null ? "none" : workId) + "-thread-" + (threadIdx + 1))
                    .ownerId(ownerId)
                    .platform("xiaohongshu")
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
        log.info("[Comment] 模拟采集完成: workId={}, count={}", workId, comments.size());
        return comments;
    }

    /**
     * 真实平台采集入口（预留）：当前返回空并记录日志，接入小红书开放平台后替换。
     */
    public List<Comment> collectFromPlatform(String workId, String ownerId) {
        log.warn("[Comment] 真实平台采集未接入，回退模拟数据: workId={}", workId);
        return collectMockComments(workId, ownerId);
    }

}
