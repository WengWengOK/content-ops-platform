package com.contentops.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评论采集任务运行记录（可观测性：每轮定时/手动采集的执行结果）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "评论采集任务运行记录")
public class CommentJobRun {

    private String runId;
    private String triggerType;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private int worksScanned;
    private int commentsCollected;
    private int commentsNew;
    private int analyzed;
    private int failed;
    private String source;
    private String detail;
}