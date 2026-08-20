package com.contentops.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 评论分析服务：对单条评论做意图识别 + 情感分析 + 摘要，并生成 AI 回复草稿。
 *
 * <p>优先调用 LLM（formatting 档，低成本），输出严格 JSON；模型不可用或
 * 输出无法解析时自动降级为关键词启发式分析，保证 MVP 全链路可跑通。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentAnalysisService {

    private final @Qualifier("formattingChatModel") ChatModel chatModel;
    private final ObjectMapper objectMapper;

    private static final String ANALYSIS_PROMPT = """
            你是小红书博主的评论区运营助手。请分析下面这条用户评论，输出严格 JSON（不要输出任何多余文字）：
            {
              "intent": "咨询|求教程|售后|吐槽|表扬|推广|潜在客户|无关",
              "sentiment": "POSITIVE|NEGATIVE|NEUTRAL",
              "summary": "一句话摘要（≤30字）",
              "reply": "给这条评论的回复草稿（自然口语化、有小红书风格，≤80字，不要暴露自己是 AI）"
            }

            评论内容：%s
            """;

    /**
     * 分析单条评论并返回（不落库，由调用方决定写入）。
     */
    public Comment analyze(Comment comment) {
        if (comment == null || comment.getContent() == null || comment.getContent().isBlank()) {
            return comment;
        }
        try {
            String raw = chatModel.chat(ANALYSIS_PROMPT.formatted(truncate(comment.getContent(), 500)));
            JsonNode node = extractJson(raw);
            if (node != null) {
                comment.setIntent(text(node, "intent"));
                comment.setSentiment(text(node, "sentiment").toUpperCase());
                comment.setAiSummary(text(node, "summary"));
                comment.setAiReply(text(node, "reply"));
                comment.setReplyStatus("DRAFT");
                log.info("[Comment] AI 分析完成: id={}, intent={}, sentiment={}",
                        comment.getCommentId(), comment.getIntent(), comment.getSentiment());
                return comment;
            }
            log.warn("[Comment] LLM 输出解析失败，降级启发式分析: id={}", comment.getCommentId());
        } catch (Exception e) {
            log.warn("[Comment] LLM 分析失败，降级启发式分析: id={}, err={}",
                    comment.getCommentId(), e.getMessage());
        }
        applyHeuristic(comment);
        comment.setReplyStatus("DRAFT");
        return comment;
    }

    /**
     * 关键词启发式分析（LLM 不可用时的兜底）。
     */
    private void applyHeuristic(Comment comment) {
        String content = comment.getContent() == null ? "" : comment.getContent();
        String intent = "无关";
        String sentiment = "NEUTRAL";

        if (containsAny(content, "教程", "怎么", "如何", "求", "步骤", "链接", "相机", "滤镜", "坐标")) {
            intent = "咨询";
        } else if (containsAny(content, "蹲", "已收藏", "坐等", "转发")) {
            intent = "潜在客户";
        } else if (containsAny(content, "差评", "效果一般", "泄露", "贵", "退款", "售后")) {
            intent = "售后";
            sentiment = "NEGATIVE";
        } else if (containsAny(content, "恰饭", "广告", "就这", "我上我也行", "标题和内容不符")) {
            intent = "吐槽";
            sentiment = "NEGATIVE";
        } else if (containsAny(content, "好", "清楚", "实用", "关注", "加油", "厉害")) {
            intent = "表扬";
            sentiment = "POSITIVE";
        } else if (containsAny(content, "浅", "深入", "展开", "数据", "案例", "开头")) {
            intent = "反馈";
        } else if (containsAny(content, "平替", "优惠", "价格", "下单", "入手")) {
            intent = "潜在客户";
        }

        comment.setIntent(intent);
        comment.setSentiment(sentiment);
        comment.setAiSummary(content.length() > 30 ? content.substring(0, 30) + "…" : content);
        comment.setAiReply("谢谢你的评论呀～已收到你的反馈，我会继续优化内容，有问题随时留言哦！");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText().trim() : "";
    }

    private JsonNode extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
