package com.contentops.common.methodology;

import com.contentops.common.enums.AgentStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 人工行动清单生成器（v2.2.0 方法论：「辅助而非替代」）。
 *
 * <p>方法论约束：每个 Agent 阶段的输出仅为「辅助」而非「替代」——AI 负责提效与建议，
 * 但选题决策、内容润色、合规审核、发布时机、异常解读、策略评估等关键环节必须由人确认。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #generateChecklist(AgentStage, Map)} — 根据阶段类型生成差异化的人工检查项</li>
 *   <li>{@link #defaultChecklist(AgentStage)} — 取阶段内置默认清单（供运维参考与配置回退）</li>
 * </ul>
 *
 * <p>清单内容可被 {@link ChecklistProperties#getStageItems()} 覆盖：若运维在配置中为某阶段
 * 自定义了检查项，则以配置为准；否则使用代码内置的默认清单。
 */
@Slf4j
@Component
public class HumanActionChecklistGenerator {

    private final ChecklistProperties properties;

    public HumanActionChecklistGenerator(ChecklistProperties properties) {
        this.properties = properties;
        log.info("HumanActionChecklistGenerator initialized: enabled={}, minItems={}",
                properties.isEnabled(), properties.getMinItems());
    }

    /**
     * 为指定 Agent 阶段的输出生成「需要人工行动」的清单。
     *
     * <p>生成逻辑：
     * <ol>
     *   <li>功能关闭时返回空列表</li>
     *   <li>优先采用 {@link ChecklistProperties#getStageItems()} 中该阶段的自定义配置</li>
     *   <li>未配置时回退到 {@link #defaultChecklist(AgentStage)} 的内置清单</li>
     *   <li>追加基于 {@code outputs} 的动态检查项（如检测到风险指标则提示人工介入）</li>
     * </ol>
     *
     * @param stage   Agent 阶段
     * @param outputs 该阶段的输出键值（用于生成动态检查项，可为空）
     * @return 人工行动检查项列表；功能关闭或 stage 为空时返回空列表
     */
    public List<String> generateChecklist(AgentStage stage, Map<String, Object> outputs) {
        if (!properties.isEnabled()) {
            log.debug("Checklist generation disabled, return empty list");
            return Collections.emptyList();
        }
        if (stage == null) {
            log.warn("generateChecklist received null stage, return empty list");
            return Collections.emptyList();
        }

        List<String> checklist = new ArrayList<>(resolveConfiguredItems(stage));
        if (checklist.isEmpty()) {
            checklist.addAll(defaultChecklist(stage));
        }

        // 基于输出的动态检查项
        List<String> dynamic = generateDynamicItems(stage, outputs);
        checklist.addAll(dynamic);

        if (checklist.size() < properties.getMinItems()) {
            log.warn("Checklist for {} has only {} items (< minItems={}); consider configuring more",
                    stage.getCode(), checklist.size(), properties.getMinItems());
        }

        log.info("Generated checklist for {}: {} items", stage.getCode(), checklist.size());
        return checklist;
    }

    /**
     * 返回指定阶段的内置默认清单（不读取配置覆盖）。
     * <p>供运维参考 {@link ChecklistProperties#getStageItems()} 应配置哪些内容。
     *
     * @param stage Agent 阶段
     * @return 默认检查项列表（不可变副本）
     */
    public List<String> defaultChecklist(AgentStage stage) {
        if (stage == null) {
            return Collections.emptyList();
        }
        return switch (stage) {
            case TOPIC_PLANNING -> List.of(
                    "人工确认选题方向是否符合账号定位与品牌调性",
                    "调整目标受众画像，核对人群假设是否准确",
                    "复核竞品分析结论的时效性与数据来源",
                    "确认是否需要补充自有差异化角度"
            );
            case CONTENT_CREATION -> List.of(
                    "人工润色文案，统一语气与个人风格",
                    "事实核查：核对数据、引用、案例的真实性",
                    "补充个人经历与独家观点，增强不可替代性",
                    "校对错别字、标点与排版结构"
            );
            case IMAGE_DESIGN -> List.of(
                    "人工审核图片合规性（敏感元素、政治风险）",
                    "版权检查：确认素材来源与授权范围",
                    "核对各平台封面尺寸与构图适配",
                    "确认人物肖像与商标使用授权"
            );
            case PUBLISHING -> List.of(
                    "人工确认发布时间是否符合平台流量规律",
                    "平台账号检查：登录态、权限、配额是否正常",
                    "复核标题与封面在目标平台的合规性",
                    "确认多平台分发顺序与互斥策略"
            );
            case DATA_ANALYSIS -> List.of(
                    "人工解读异常数据，排除统计口径与采集错误",
                    "制定下一步行动计划：放大有效策略、止损低效方向",
                    "复核趋势结论是否被单篇爆款/低效干扰",
                    "确认数据采样周期与对比基线的合理性"
            );
            case OPTIMIZATION -> List.of(
                    "人工评估策略调整的可行性与资源成本",
                    "确认调整方向不违背长期账号定位",
                    "评估策略切换的过渡风险与回滚方案",
                    "确认是否需要小范围灰度验证后再全量"
            );
        };
    }

    // ──────────────────── 内部工具方法 ────────────────────

    /**
     * 读取配置中该阶段的自定义检查项；未配置返回空列表。
     */
    private List<String> resolveConfiguredItems(AgentStage stage) {
        Map<String, List<String>> stageItems = properties.getStageItems();
        if (stageItems == null || stageItems.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> items = stageItems.get(stage.name());
        if (items == null) {
            // 兼容以 stage code 作为 key 的配置写法
            items = stageItems.get(stage.getCode());
        }
        return items != null ? new ArrayList<>(items) : Collections.emptyList();
    }

    /**
     * 基于阶段输出动态追加检查项。
     * <p>例如：数据分析阶段若检测到负向指标，提示人工优先介入。
     */
    private List<String> generateDynamicItems(AgentStage stage, Map<String, Object> outputs) {
        List<String> dynamic = new ArrayList<>();
        if (outputs == null || outputs.isEmpty()) {
            return dynamic;
        }

        switch (stage) {
            case DATA_ANALYSIS -> {
                if (containsNegativeSignal(outputs)) {
                    dynamic.add("【动态】检测到负向/异常指标，请人工优先排查原因并制定止损方案");
                }
            }
            case PUBLISHING -> {
                if (outputs.containsKey("failedPlatforms") || outputs.containsKey("errors")) {
                    dynamic.add("【动态】存在发布失败的平台，请人工核查账号状态并重试");
                }
            }
            case CONTENT_CREATION -> {
                Object wordCount = outputs.get("wordCount");
                if (wordCount instanceof Number n && n.intValue() > 3000) {
                    dynamic.add("【动态】初稿篇幅较长，建议人工评估是否拆分为系列内容");
                }
            }
            case TOPIC_PLANNING -> {
                if (outputs.containsKey("trendingKeywords") && outputs.get("trendingKeywords") == null) {
                    dynamic.add("【动态】未检索到热点关键词，请人工补充领域热点输入");
                }
            }
            default -> {
                // 其它阶段暂无动态规则
            }
        }
        return dynamic;
    }

    /**
     * 检测数据分析输出中是否含负向信号（负值、下降、异常关键词）。
     */
    private boolean containsNegativeSignal(Map<String, Object> outputs) {
        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Number n && n.doubleValue() < 0) {
                return true;
            }
            if (v instanceof String s) {
                String lower = s.toLowerCase();
                if (lower.contains("下降") || lower.contains("下跌") || lower.contains("异常")
                        || lower.contains("暴跌") || lower.contains("警告") || lower.contains("风险")) {
                    return true;
                }
            }
        }
        return false;
    }
}
