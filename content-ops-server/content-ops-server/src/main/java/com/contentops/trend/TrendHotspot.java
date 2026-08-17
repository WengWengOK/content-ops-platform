package com.contentops.trend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 热点条目：多平台热榜聚合的最小单元，供选题模块直接取热点生成作品。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "热点条目")
public class TrendHotspot {

    @Schema(description = "热点 ID（可用于直接选题）")
    private String id;

    @Schema(description = "来源平台 code：xiaohongshu / weibo / douyin / bilibili / zhihu")
    private String platform;

    @Schema(description = "热点标题")
    private String title;

    @Schema(description = "原文链接")
    private String url;

    @Schema(description = "热度值（各平台口径不同，仅作排序参考）")
    private Long heat;

    @Schema(description = "平台内排名")
    private Integer rank;

    @Schema(description = "分类/标签，如 科技 / 娱乐 / 社会")
    private String category;

    @Schema(description = "一句话摘要（可选）")
    private String summary;

    @Schema(description = "抓取时间（快照时间）")
    private LocalDateTime capturedAt;

    @Schema(description = "AI 分析结果（相关性/可信度/摘要，可空）")
    private TrendAnalysis analysis;

    @Schema(description = "突发热点标记：新上榜 / 飙升 / 上升（可空）")
    private String burstLabel;

    @Schema(description = "较上一快照的热度增量（可空）")
    private Long heatDelta;

    @Schema(description = "较上一快照的排名上升数（正数=上升，可空）")
    private Integer rankDelta;

    @Schema(description = "上一快照热度（突发热点事件回溯用）")
    private Long prevHeat;

    @Schema(description = "上一快照排名")
    private Integer prevRank;

    @Schema(description = "是否首次上榜（当前快照之前从未出现）")
    private Boolean isNew;

    @Schema(description = "该主题首次出现时间（用于回溯上榜时长）")
    private LocalDateTime firstSeenAt;

    @Schema(description = "爆发得分（热度涨幅折算 + 排名上升），越高越值得关注")
    private Integer burstScore;
}
