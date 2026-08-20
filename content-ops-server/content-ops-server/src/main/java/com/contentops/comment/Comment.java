package com.contentops.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 评论区助手评论条目（MVP：小红书）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "平台评论条目")
public class Comment {

    @Schema(description = "评论 ID")
    private String commentId;

    @Schema(description = "所属用户 ID（租户隔离）")
    private String ownerId;

    @Schema(description = "平台：xiaohongshu")
    private String platform;

    @Schema(description = "作品/笔记 ID")
    private String workId;

    @Schema(description = "关联工作流 ID（可空）")
    private String workflowId;

    @Schema(description = "评论人")
    private String author;

    @Schema(description = "评论内容")
    private String content;

    @Schema(description = "点赞数")
    private Integer likes;

    @Schema(description = "评论时间")
    private LocalDateTime commentTime;

    @Schema(description = "回复对象")
    private String replyTo;

    @Schema(description = "意图：咨询/求教程/售后/吐槽/表扬/推广/潜在客户/无关")
    private String intent;

    @Schema(description = "情感：POSITIVE/NEGATIVE/NEUTRAL")
    private String sentiment;

    @Schema(description = "AI 摘要")
    private String aiSummary;

    @Schema(description = "AI 回复草稿")
    private String aiReply;

    @Schema(description = "回复状态：NONE/DRAFT/APPROVED/SENT")
    private String replyStatus;

    @Schema(description = "对话历史（JSON）")
    private String dialogHistory;

    @Schema(description = "采集时间")
    private LocalDateTime collectedAt;
}
