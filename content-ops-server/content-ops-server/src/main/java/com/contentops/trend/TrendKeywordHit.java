package com.contentops.trend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 关键词命中记录：启用中的监控方向在某次轮询快照中匹配到的热点条目。
 *
 * <p>每次刷新热点快照时，系统按用户已启用的关键词对全量热点做匹配并落库，
 * 形成「关键词 → 平台 → 时间」的命中轨迹，供突发热点检测与通知（P1）使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "关键词命中记录")
public class TrendKeywordHit {

    @Schema(description = "命中记录 ID")
    private String hitId;

    @Schema(description = "订阅归属用户 ID")
    private String ownerId;

    @Schema(description = "监控关键词")
    private String keyword;

    @Schema(description = "来源平台 code")
    private String platform;

    @Schema(description = "热点标题")
    private String title;

    @Schema(description = "原文链接")
    private String url;

    @Schema(description = "热度值")
    private Long heat;

    @Schema(description = "平台内排名")
    private Integer rank;

    @Schema(description = "分类/标签")
    private String category;

    @Schema(description = "一句话摘要")
    private String summary;

    @Schema(description = "命中时间（快照时间）")
    private LocalDateTime capturedAt;
}
