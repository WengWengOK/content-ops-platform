package com.contentops.comment;

import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.common.security.AuthContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论 AI 对话服务：多轮对话（复用 dialog_history 字段）、
 * 回复草稿状态机 NONE → DRAFT → APPROVED → SENT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentReplyService {

    private final CommentRepository repository;
    private final ObjectMapper objectMapper;
    private final @Qualifier("formattingChatModel") ChatModel chatModel;

    private static final String CHAT_PROMPT = """
            你是小红书博主的评论区回复助手，用自然口语化、有小红书风格的中文回复用户。
            不要暴露自己是 AI。基于用户评论原文和对话历史，回复用户最新一条消息。
            直接输出回复内容本身，不要加任何前缀或引号。

            用户评论原文：%s

            对话历史：
            %s

            用户最新消息：%s
            """;

    /**
     * 多轮 AI 对话：追加用户消息 → 生成回复 → 写回 dialog_history（状态保持 DRAFT）。
     */
    public Comment chat(String commentId, String message) {
        Comment comment = findAndCheckOwner(commentId);
        if (message == null || message.isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "消息不能为空");
        }

        List<Map<String, String>> history = loadHistory(comment.getDialogHistory());
        history.add(Map.of("role", "user", "content", message.trim()));

        String reply;
        try {
            String prompt = CHAT_PROMPT.formatted(
                    safe(comment.getContent()), formatHistory(history), message.trim());
            reply = chatModel.chat(prompt).trim();
            if (reply.isBlank()) {
                reply = fallbackReply(comment, message);
            }
        } catch (Exception e) {
            log.warn("[Comment] AI 对话失败，降级回复: id={}, err={}", commentId, e.getMessage());
            reply = fallbackReply(comment, message);
        }
        history.add(Map.of("role", "assistant", "content", reply));

        String json;
        try {
            json = objectMapper.writeValueAsString(history);
        } catch (Exception e) {
            json = "[]";
        }
        repository.updateDialog(commentId, json);
        repository.updateReply(commentId, reply, "DRAFT");

        return repository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "评论不存在"));
    }

    /**
     * 审核通过：DRAFT → APPROVED。
     */
    public Comment approve(String commentId) {
        Comment comment = findAndCheckOwner(commentId);
        if (!"DRAFT".equals(comment.getReplyStatus())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "只有草稿状态的回复可以审核通过");
        }
        repository.updateReply(commentId, comment.getAiReply(), "APPROVED");
        return repository.findById(commentId).orElse(comment);
    }

    /**
     * 发送：APPROVED → SENT（MVP 记录已发送状态，真实发布接入平台 API）。
     */
    public Comment send(String commentId) {
        Comment comment = findAndCheckOwner(commentId);
        if (!"APPROVED".equals(comment.getReplyStatus())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "请先审核通过再发送");
        }
        repository.updateReply(commentId, comment.getAiReply(), "SENT");
        log.info("[Comment] 评论回复已发送（模拟）: id={}", commentId);
        return repository.findById(commentId).orElse(comment);
    }

    /**
     * 人工修改回复内容 / 指定状态。
     */
    public Comment updateReply(String commentId, String reply, String status) {
        Comment comment = findAndCheckOwner(commentId);
        if (reply != null && !reply.isBlank()) {
            repository.updateReply(commentId, reply.trim(), status == null ? "DRAFT" : status);
        } else if (status != null) {
            repository.updateReply(commentId, comment.getAiReply(), status);
        } else {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "回复内容或状态不能都为空");
        }
        return repository.findById(commentId).orElse(comment);
    }

    private Comment findAndCheckOwner(String commentId) {
        Comment comment = repository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "评论不存在"));
        String ownerId = AuthContext.currentUserId();
        if (ownerId != null && comment.getOwnerId() != null && !ownerId.equals(comment.getOwnerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该评论");
        }
        return comment;
    }

    private List<Map<String, String>> loadHistory(String dialogHistory) {
        if (dialogHistory == null || dialogHistory.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(dialogHistory, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String formatHistory(List<Map<String, String>> history) {
        if (history.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> turn : history) {
            sb.append(turn.getOrDefault("role", "?")).append(": ")
                    .append(turn.getOrDefault("content", "")).append("\n");
        }
        return sb.toString();
    }

    private String fallbackReply(Comment comment, String message) {
        String content = safe(comment.getContent());
        if (content.contains("教程") || content.contains("怎么") || message.contains("怎么")) {
            return "收到！教程正在整理中，先收藏不迷路，更新了第一时间通知你～";
        }
        if (content.contains("平替") || content.contains("价格") || content.contains("贵")) {
            return "性价比这块我也很在意，可以蹲一下我后续的平价版测评，会有惊喜哦！";
        }
        return "谢谢你的评论呀～你的建议我记下来啦，有新的进展会第一时间更新！";
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
