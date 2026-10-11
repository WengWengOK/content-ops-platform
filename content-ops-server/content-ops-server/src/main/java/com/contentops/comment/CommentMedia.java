package com.contentops.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评论附件（图片/视频）——「评论即消息」模型里的消息体附件。
 *
 * <p>为什么单独建表而不是塞进评论行：一条评论可带多张图，且每张图需要独立的
 * 类型/尺寸/所属用户（{@link #ownerUserId}/{@link #ownerName}）等属性；
 * 评论主表只保留 {@code media_count} 作为「是否带图」的紧凑标记，便于筛选与索引。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "评论附件")
public class CommentMedia {

    @Schema(description = "附件 ID")
    private String mediaId;

    @Schema(description = "所属评论 ID")
    private String commentId;

    @Schema(description = "类型：IMAGE / VIDEO")
    private String mediaType;

    @Schema(description = "原图地址")
    private String url;

    @Schema(description = "缩略图地址（可选）")
    private String thumbUrl;

    private Integer width;

    private Integer height;

    @Schema(description = "同一评论内的排序")
    private Integer sortOrder;

    @Schema(description = "附件所属用户 ID（评论作者）")
    private String ownerUserId;

    @Schema(description = "附件所属用户昵称（评论作者名，展示用）")
    private String ownerName;

    @Schema(description = "预留扩展字段（JSON）")
    private String extraJson;

    private LocalDateTime createdAt;
}