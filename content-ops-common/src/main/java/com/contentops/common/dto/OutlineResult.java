package com.contentops.common.dto;

import lombok.*;

import java.util.List;

/**
 * 内容创作第一阶段（大纲生成）的结构化输出。
 *
 * <p>渐进式生成策略：先生成大纲 → 人工确认 → 再基于确认的大纲生成完整初稿。
 * 这样可以「先搭框架，别一步到位」，避免方向跑偏后浪费大量返工成本。
 *
 * <p>此 DTO 对应 {@code ContentCreationAgent.generateOutline()} 的返回值，
 * 是 {@link ContentDraftResult} 的子集（仅含大纲部分，不含初稿正文）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutlineResult {

    /** 文章标题（暂定） */
    private String title;

    /** 文章框架大纲 */
    private ContentDraftResult.ArticleOutline outline;

    /** 每个段落的写作要点提示 */
    private List<String> writingNotes;

    /** 参考的历史文章来源（来自知识库检索） */
    private List<String> references;

    /** 预计字数 */
    private int estimatedWordCount;

    /** 切入角度说明 */
    private String angle;
}
