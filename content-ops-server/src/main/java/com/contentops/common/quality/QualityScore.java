package com.contentops.common.quality;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 质量评分结果 DTO（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>包含三个维度的评分（各 0-100 分）、总评总分，以及针对性的改进建议。
 * 由 {@link QualityAssessmentService#assessQuality} 返回。
 *
 * <h3>三维评分维度</h3>
 * <ul>
 *   <li><b>逻辑性 (logic)</b>：内容结构完整性、逻辑连接词使用、段落层次</li>
 *   <li><b>可读性 (readability)</b>：内容长度、Markdown 格式化、段落分布</li>
 *   <li><b>原创性 (originality)</b>：词汇多样性、关键词覆盖、重复度</li>
 * </ul>
 *
 * @see QualityAssessmentService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityScore {

    /** 逻辑性评分（0-100），评估内容结构、逻辑连贯性 */
    private int logic;

    /** 可读性评分（0-100），评估排版格式、内容长度、段落分布 */
    private int readability;

    /** 原创性评分（0-100），评估词汇多样性、内容重复度 */
    private int originality;

    /** 总评总分（0-100），三个维度的加权平均 */
    private int totalScore;

    /** 改进建议列表，每个建议针对某个评分较低的维度 */
    @Builder.Default
    private List<String> suggestions = new ArrayList<>();

    /**
     * 判断总分是否达到指定阈值。
     *
     * @param threshold 最低质量阈值（0-100）
     * @return true 表示质量达标
     */
    public boolean isAboveThreshold(int threshold) {
        return totalScore >= threshold;
    }

    /**
     * 获取三个维度中最低的分数。
     *
     * @return 最低维度分数
     */
    public int getLowestDimensionScore() {
        return Math.min(Math.min(logic, readability), originality);
    }

    /**
     * 获取分数最低的维度名称。
     *
     * @return "logic"、"readability" 或 "originality"
     */
    public String getWeakestDimension() {
        if (logic <= readability && logic <= originality) {
            return "logic";
        } else if (readability <= originality) {
            return "readability";
        } else {
            return "originality";
        }
    }
}
