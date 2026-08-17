package com.contentops.common.collection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 合集内单个作品摘要（从工作流状态实时解析，不冗余存储正文）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "合集内作品摘要")
public class CollectionWork {

    @Schema(description = "工作流（作品）ID")
    private String workflowId;

    @Schema(description = "作品标题")
    private String title;

    @Schema(description = "作品状态（COMPLETED / FAILED / ...）")
    private String status;

    @Schema(description = "目标平台名称列表")
    private List<String> platforms;

    @Schema(description = "发布模式：text-cover / image-text / full-image")
    private String publishMode;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
