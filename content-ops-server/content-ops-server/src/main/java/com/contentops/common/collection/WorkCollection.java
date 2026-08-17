package com.contentops.common.collection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 作品合集：用户自建的分类收藏集，用于按类型（干货/情感/种草等）归集同类作品。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "作品合集")
public class WorkCollection {

    @Schema(description = "合集 ID")
    private String collectionId;

    @Schema(description = "归属用户 ID（鉴权开启时用于数据隔离）")
    private String ownerId;

    @Schema(description = "合集名称", example = "职场干货")
    private String name;

    @Schema(description = "合集类型（按类型区分），如 干货知识 / 情感故事 / 产品种草 / 个人成长 / 其他")
    private String type;

    @Schema(description = "合集描述")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "合集内作品数量（列表接口返回）")
    private int workCount;

    @Schema(description = "合集内作品摘要（详情接口返回）")
    private List<CollectionWork> works;
}
