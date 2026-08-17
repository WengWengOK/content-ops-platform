package com.contentops.orchestrator.workflow;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.methodology.HumanActionChecklistGenerator;
import com.contentops.common.quality.QualityAssessmentService;
import com.contentops.common.quality.QualityScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 质量评估增强器 — 负责阶段输出的质量评估与人工行动清单生成。
 *
 * <p>从 {@link PipelineOrchestrator} 拆分而来（P2-13），保持原有逻辑不变。
 *
 * <p>将质量评分和行动清单存储到 accumulatedArtifacts 中，供前端展示和后续优化参考。
 * 质量评分不阻断流程（低分仅记录警告），由 AutoRetryService 在需要时触发重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityEnricher {

    private final QualityAssessmentService qualityAssessmentService;
    private final HumanActionChecklistGenerator checklistGenerator;

    /**
     * P2 集成：对阶段输出进行质量评估，并生成人工行动清单。
     *
     * @param context 工作流上下文
     * @param stage   当前阶段
     * @param data    阶段输出数据
     */
    @SuppressWarnings("unchecked")
    void assessAndEnrich(TaskContext context, AgentStage stage, Map<String, Object> data) {
        if (data == null) return;

        try {
            // 提取文本内容用于质量评估
            String content = extractTextContent(data);

            // 质量评估
            QualityScore qualityScore = qualityAssessmentService.assessQuality(stage, content);
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            Map<String, Object> qualityMeta = new java.util.HashMap<>();
            qualityMeta.put("score", qualityScore.getTotalScore());
            qualityMeta.put("logic", qualityScore.getLogic());
            qualityMeta.put("readability", qualityScore.getReadability());
            qualityMeta.put("originality", qualityScore.getOriginality());
            qualityMeta.put("suggestions", qualityScore.getSuggestions());
            context.getAccumulatedArtifacts().put(stage.getCode() + ":quality", qualityMeta);

            if (qualityScore.getTotalScore() < 60) {
                log.warn("[Workflow:{}] Stage {} quality score {} below threshold. Suggestions: {}",
                        context.getWorkflowId(), stage.getCode(),
                        qualityScore.getTotalScore(), qualityScore.getSuggestions());
            } else {
                log.info("[Workflow:{}] Stage {} quality score: {} (logic={}, readability={}, originality={})",
                        context.getWorkflowId(), stage.getCode(),
                        qualityScore.getTotalScore(),
                        qualityScore.getLogic(), qualityScore.getReadability(),
                        qualityScore.getOriginality());
            }

            // 人工行动清单（"帮助而非替代"方法论）
            List<String> checklist = checklistGenerator.generateChecklist(stage, data);
            if (checklist != null && !checklist.isEmpty()) {
                context.getAccumulatedArtifacts().put(stage.getCode() + ":checklist", checklist);
                log.info("[Workflow:{}] Stage {} generated {} human action items",
                        context.getWorkflowId(), stage.getCode(), checklist.size());
            }
        } catch (Exception e) {
            log.warn("[Workflow:{}] Quality assessment failed for stage {}, continuing pipeline: {}",
                    context.getWorkflowId(), stage.getCode(), e.getMessage());
        }
    }

    /**
     * 从阶段输出数据中提取文本内容用于质量评估。
     */
    private String extractTextContent(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        for (Object value : data.values()) {
            if (value instanceof String s && !s.isBlank()) {
                sb.append(s).append("\n");
            } else if (value instanceof Map<?, ?> m) {
                extractTextFromMap(m, sb);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        sb.append(s).append("\n");
                    } else if (item instanceof Map<?, ?> m) {
                        extractTextFromMap(m, sb);
                    }
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void extractTextFromMap(Map<?, ?> map, StringBuilder sb) {
        for (Object value : map.values()) {
            if (value instanceof String s && !s.isBlank()) {
                sb.append(s).append("\n");
            } else if (value instanceof Map<?, ?> m) {
                extractTextFromMap(m, sb);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        sb.append(s).append("\n");
                    } else if (item instanceof Map<?, ?> m2) {
                        extractTextFromMap(m2, sb);
                    }
                }
            }
        }
    }
}
