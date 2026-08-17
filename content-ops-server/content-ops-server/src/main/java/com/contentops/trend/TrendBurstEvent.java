package com.contentops.trend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 突发热点事件：某次轮询快照中检测到的「新上榜 / 飙升 / 上升」条目。
 *
 * <p>爆发只发生在快照对比的那一刻，若不落库会随下一轮快照消失；
 * 持久化后支持历史回溯与 P1 通知（WebSocket/邮件）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "突发热点事件")
public class TrendBurstEvent {

    @Schema(description = "事件 ID")
    private String eventId;

    @Schema(description = "来源平台 code")
    private String platform;

    @Schema(description = "热点标题")
    private String title;

    @Schema(description = "原文链接")
    private String url;

    @Schema(description = "当前热度")
    private Long heat;

    @Schema(description = "上一快照热度")
    private Long prevHeat;

    @Schema(description = "当前排名")
    private Integer rank;

    @Schema(description = "上一快照排名")
    private Integer prevRank;

    @Schema(description = "热度增量")
    private Long heatDelta;

    @Schema(description = "排名上升数")
    private Integer rankDelta;

    @Schema(description = "爆发标记：新上榜 / 飙升 / 上升")
    private String burstLabel;

    @Schema(description = "爆发得分")
    private Integer burstScore;

    @Schema(description = "检测时间")
    private LocalDateTime capturedAt;
}
