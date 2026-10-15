package com.contentops.common.knowledge.qa;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库问答记录（含五层错误识别结果）。
 *
 * <p>一行 = 一次问答 + 它的全部质量信号：
 * 检索侧信号（{@code retrieval}）、引用校验（{@code citationCheck}）、
 * 忠实度报告（{@code faithfulness}）、风险等级与复核状态、隐式信号命中（{@code suspectedError}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "知识库问答记录")
public class QaRecord {

    private String qaId;

    @Schema(description = "所属租户")
    private String ownerId;

    private String question;

    private String answer;

    @Schema(description = "是否拒答（检索不充分）")
    private boolean refused;

    @Schema(description = "答案来源：llm / template / refuse")
    private String source;

    @Schema(description = "引用明细 JSON：[{index,chunkId,title,score}]")
    private String citations;

    @Schema(description = "引用校验 JSON")
    private String citationCheck;

    @Schema(description = "检索侧评估 JSON：relevantCount/sufficient/signals/reason/rewriteRounds")
    private String retrieval;

    @Schema(description = "忠实度报告 JSON：ratio + 各结论判定")
    private String faithfulness;

    @Schema(description = "综合风险等级：NONE/LOW/MEDIUM/HIGH")
    private String riskLevel;

    @Schema(description = "复核状态：NONE/SAMPLED/NEEDS_REVIEW/REVIEWED")
    private String reviewState;

    @Schema(description = "是否被隐式信号标为「疑似错误」")
    private boolean suspectedError;

    private String suspectedReason;

    private String traceId;

    private Long latencyMs;

    private Integer promptTokens;

    private Integer completionTokens;

    private LocalDateTime createdAt;
}
