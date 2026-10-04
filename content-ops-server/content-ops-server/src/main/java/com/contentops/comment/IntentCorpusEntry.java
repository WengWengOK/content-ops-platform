package com.contentops.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 意图语料库条目：把「常见说法」映射到「标准术语」。
 *
 * <p>用于意图识别的 RAG 前置匹配：相似度 ≥ 阈值直接采用标准术语，未命中再交给模型判断，
 * 既降低模型调用与 token 消耗，也让高频表达的判定更稳定、可解释。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "意图语料库条目")
public class IntentCorpusEntry {

    @Schema(description = "条目 ID")
    private String corpusId;

    @Schema(description = "归属租户；为空表示全局共享语料")
    private String ownerId;

    @Schema(description = "标准术语（意图），如 咨询/求教程/售后")
    private String intent;

    @Schema(description = "语料原文（用户常见说法）")
    private String phrase;

    @Schema(description = "来源：SEED（内置）/ USER（用户维护）")
    private String source;

    @Schema(description = "是否启用")
    private boolean enabled;

    @Schema(description = "命中次数（观测用）")
    private int hitCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}