package com.contentops.common.knowledge.qa;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 问答的隐式/显式反馈信号（用户没点踩也能捕获问题）。
 *
 * <p>信号类型：
 * <ul>
 *   <li>{@code EXPLICIT_DISLIKE} —— 用户点踩；</li>
 *   <li>{@code DOUBT_REPHRASE} —— 追问「不对吧」「还是找不到」，说明上个答案没解决；</li>
 *   <li>{@code REPEAT_QUESTION} —— 短时间内重复提同一个问题；</li>
 *   <li>{@code CITATION_BOUNCE} —— 点开引用章节很快返回（引用没帮上忙）；</li>
 *   <li>{@code HANDOFF_CONFIRMED} —— 转人工后实施确认答案错误。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "问答反馈信号")
public class QaSignal {

    private String signalId;

    private String qaId;

    private String ownerId;

    @Schema(description = "EXPLICIT_DISLIKE / DOUBT_REPHRASE / REPEAT_QUESTION / CITATION_BOUNCE / HANDOFF_CONFIRMED")
    private String signalType;

    /** 信号可信度：0~1（越接近 1 越确定是错误样本） */
    private Double confidence;

    private String detail;

    @Schema(description = "关联的上一条问答（追问/重复提问时）")
    private String relatedQaId;

    private LocalDateTime createdAt;
}
