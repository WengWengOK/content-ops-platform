package com.contentops.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评论自动采集监控项：把「某平台的某个作品」加入监控后，定时任务会周期性拉取新评论。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "评论自动采集监控项")
public class CommentWatch {

    @Schema(description = "监控项 ID")
    private String watchId;

    @Schema(description = "所属用户 ID（租户隔离）")
    private String ownerId;

    @Schema(description = "平台：xiaohongshu 等")
    private String platform;

    @Schema(description = "作品/笔记 ID")
    private String workId;

    @Schema(description = "关联工作流 ID（可空）")
    private String workflowId;

    @Schema(description = "笔记访问票据 xsec_token（自建桥必填，有有效期需更新）")
    private String xsecToken;

    @Schema(description = "是否对新评论自动做 AI 分析")
    private boolean autoAnalyze;

    @Schema(description = "是否启用")
    private boolean enabled;

    @Schema(description = "上次采集时间")
    private LocalDateTime lastCollectedAt;

    @Schema(description = "上次新增评论数")
    private int lastNewCount;

    @Schema(description = "累计采集评论数")
    private int totalCollected;

    @Schema(description = "上次采集使用的数据源：api/mock")
    private String lastSource;

    @Schema(description = "上次采集错误信息（成功时为空）")
    private String lastError;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}