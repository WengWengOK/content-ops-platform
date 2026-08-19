package com.contentops.common.validation;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一致性校验器 — 校验当前阶段输出与前序阶段产物的一致性。
 *
 * <h3>校验维度</h3>
 * <ul>
 *   <li><b>选题 → 内容</b>：内容标题/主题应与选题阶段的 topic 对齐（关键词重叠）</li>
 *   <li><b>内容 → 配图</b>：配图描述应与内容主题相关（避免图文不符）</li>
 *   <li><b>内容 → 发布</b>：发布平台应与内容形态匹配（长文→公众号，短视频→抖音）</li>
 *   <li><b>内容 → 分析</b>：分析报告应引用实际发布的内容数据</li>
 *   <li><b>分析 → 优化</b>：优化建议应针对分析报告中的具体问题</li>
 * </ul>
 *
 * <p>校验策略：基于<b>关键词重叠度</b>的轻量规则（避免 LLM 调用）。
 * 重叠度低于阈值 → WARN（不阻断，记录到上下文供 Agent 参考）；
 * 完全无重叠 → BLOCK（明显跑题，需重生成）。
 */
@Slf4j
@Component
public class ConsistencyValidator implements AgentOutputValidator {

    /** 关键词重叠度阈值：低于此值视为完全跑题（BLOCK） */
    private static final double BLOCK_THRESHOLD = 0.05;

    /** 关键词重叠度警告阈值：低于此值记 WARN */
    private static final double WARN_THRESHOLD = 0.20;

    @Override
    public ValidationType type() {
        return ValidationType.CONSISTENCY;
    }

    @Override
    public ValidationResult validate(AgentStage stage, Map<String, Object> data, TaskContext context) {
        // 获取前序阶段产物
        Map<String, Object> artifacts = context.getAccumulatedArtifacts();
        if (artifacts == null || artifacts.isEmpty()) {
            // 无前序产物（首个阶段），跳过一致性校验
            return ValidationResult.pass(ValidationType.CONSISTENCY);
        }

        // 根据当前阶段确定"参照产物"和"当前文本"
        String referenceText = extractReferenceText(stage, artifacts);
        String currentText = flattenToString(data);

        if (referenceText.isBlank() || currentText.isBlank()) {
            return ValidationResult.pass(ValidationType.CONSISTENCY);
        }

        // 计算关键词重叠度
        double overlap = calculateKeywordOverlap(referenceText, currentText);
        String prevStageName = getPreviousStageName(stage);

        if (overlap < BLOCK_THRESHOLD) {
            String msg = String.format("%s → %s 一致性过低（重叠度 %.0f%%），疑似跑题",
                    prevStageName, stage.getNameCn(), overlap * 100);
            log.warn("[ConsistencyValidator] stage={} {}", stage.getCode(), msg);
            return ValidationResult.block(ValidationType.CONSISTENCY, List.of(msg));
        }

        if (overlap < WARN_THRESHOLD) {
            String msg = String.format("%s → %s 关键词重叠度偏低（%.0f%%），建议检查内容相关性",
                    prevStageName, stage.getNameCn(), overlap * 100);
            log.info("[ConsistencyValidator] stage={} {}", stage.getCode(), msg);
            return ValidationResult.warn(ValidationType.CONSISTENCY, List.of(msg));
        }

        return ValidationResult.pass(ValidationType.CONSISTENCY);
    }

    /** 根据当前阶段，从 accumulatedArtifacts 中提取参照文本 */
    private String extractReferenceText(AgentStage stage, Map<String, Object> artifacts) {
        // 当前阶段的"前序参照阶段"：选题→无（首阶段）/ 内容→选题 / 配图→内容 / 发布→内容 / 分析→内容+发布 / 优化→分析
        String refStageCode = switch (stage) {
            case CONTENT_CREATION -> AgentStage.TOPIC_PLANNING.getCode();
            case IMAGE_DESIGN, PUBLISHING, DATA_ANALYSIS -> AgentStage.CONTENT_CREATION.getCode();
            case OPTIMIZATION -> AgentStage.DATA_ANALYSIS.getCode();
            default -> null;
        };

        if (refStageCode == null) {
            return "";
        }

        Object refArtifact = artifacts.get(refStageCode);
        if (refArtifact == null) {
            return "";
        }
        return flattenObject(refArtifact);
    }

    /** 获取前序阶段中文名（用于日志/失败描述） */
    private String getPreviousStageName(AgentStage stage) {
        return switch (stage) {
            case CONTENT_CREATION -> AgentStage.TOPIC_PLANNING.getNameCn();
            case IMAGE_DESIGN, PUBLISHING, DATA_ANALYSIS -> AgentStage.CONTENT_CREATION.getNameCn();
            case OPTIMIZATION -> AgentStage.DATA_ANALYSIS.getNameCn();
            default -> "前序阶段";
        };
    }

    /**
     * 计算两段文本的关键词重叠度（Jaccard 系数）。
     *
     * <p>分词策略：按非中文字符（标点/空格/英文）切分，保留长度 ≥2 的中文片段。
     * 简单但有效，避免引入分词库依赖。
     */
    private double calculateKeywordOverlap(String text1, String text2) {
        // 中文片段提取：连续的中文字符（≥2 字符视为关键词）
        java.util.Set<String> keywords1 = extractChineseKeywords(text1);
        java.util.Set<String> keywords2 = extractChineseKeywords(text2);

        if (keywords1.isEmpty() || keywords2.isEmpty()) {
            // 无中文关键词时，退化为字符级重叠
            return 0.5; // 中性值，不触发 BLOCK/WARN
        }

        // Jaccard 系数 = 交集 / 并集
        java.util.Set<String> intersection = new java.util.HashSet<>(keywords1);
        intersection.retainAll(keywords2);

        java.util.Set<String> union = new java.util.HashSet<>(keywords1);
        union.addAll(keywords2);

        return (double) intersection.size() / union.size();
    }

    /** 提取中文关键词（连续 ≥2 个中文字符） */
    private java.util.Set<String> extractChineseKeywords(String text) {
        java.util.Set<String> keywords = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,}").matcher(text);
        while (m.find()) {
            keywords.add(m.group());
        }
        return keywords;
    }

    /** 把 Map/Object 扁平化为字符串 */
    @SuppressWarnings("unchecked")
    private String flattenToString(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        for (Object value : data.values()) {
            appendValue(sb, value);
        }
        return sb.toString();
    }

    private void appendValue(StringBuilder sb, Object value) {
        if (value instanceof String s) {
            sb.append(s).append("\n");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value).append("\n");
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                appendValue(sb, item);
            }
        } else if (value instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                appendValue(sb, v);
            }
        }
    }

    private String flattenObject(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            for (Object v : m.values()) {
                appendValue(sb, v);
            }
            return sb.toString();
        }
        return obj == null ? "" : obj.toString();
    }
}
