package com.contentops.common.dto;

import lombok.*;

import java.util.List;

/**
 * 配图设计第一阶段（风格方向）的结构化输出。
 *
 * <p>渐进式生成策略：先生成 3 个风格方向 → 人工选择 → 再基于选定的风格批量生成配图。
 * 这样可以「先定调子，再出图」，避免生成了全部配图后发现风格不对、全部返工。
 *
 * <p>此 DTO 对应 {@code ImageDesignAgent.generateStyleDirections()} 的返回值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StyleDirectionResult {

    /** 从文章内容中提取的视觉关键词 */
    private List<String> visualKeywords;

    /** 3 个候选风格方向 */
    private List<StyleDirection> directions;

    /** 文章整体调性分析 */
    private String toneAnalysis;

    /**
     * 单个风格方向。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StyleDirection {

        /** 风格名称，如「日系生活感」「胶片质感」「极简插画风」 */
        private String name;

        /** 风格描述 */
        private String description;

        /** 色调建议 */
        private String colorPalette;

        /** 参考提示词前缀（生图时会在此基础上展开） */
        private String promptPrefix;

        /** 适合的段落位置建议 */
        private String suggestedPositions;

        /** 推荐指数 1-5 */
        private int recommendationScore;
    }
}
