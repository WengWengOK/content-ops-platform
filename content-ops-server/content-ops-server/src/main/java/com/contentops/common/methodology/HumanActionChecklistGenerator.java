package com.contentops.common.methodology;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.platform.MetricsParser;
import com.contentops.common.platform.MetricsParser.ParsedMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 人工行动清单生成器（v2.2.0 方法论：「辅助而非替代」，P2 优化: 基于真实数据生成动态检查项）。
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
 * <h3>P2 优化改进</h3>
 * <ul>
 *   <li><b>修复 TOPIC_PLANNING 空值检查 bug</b>：原逻辑 `outputs.get(key) == null` 误判，改为 `!containsKey`</li>
 *   <li><b>MetricsParser 集成</b>：DATA_ANALYSIS 阶段使用真实指标检测异常，生成针对性检查项</li>
 *   <li><b>更多动态规则</b>：新增 OPTIMIZATION、IMAGE_DESIGN 阶段的动态检查项</li>
 * </ul>
 *
 * <p>清单内容可被 {@link ChecklistProperties#getStageItems()} 覆盖：若运维在配置中为某阶段
 * 自定义了检查项，则以配置为准；否则使用代码内置的默认清单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HumanActionChecklistGenerator {

    private final ChecklistProperties properties;
    private final MetricsParser metricsParser;

    /**
     * 为指定 Agent 阶段的输出生成「需要人工行动」的清单。
     *
     * <p>生成逻辑：
     * <ol>
     *   <li>功能关闭时返回空列表</li>
     *   <li>优先采用 {@link ChecklistProperties#getStageItems()} 中该阶段的自定义配置</li>
     *   <li>未配置时回退到 {@link #defaultChecklist(AgentStage)} 的内置清单</li>
     *   <li>追加基于 {@code outputs} 的动态检查项（P2 优化: 基于真实指标）</li>
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

        // 基于输出的动态检查项（P2 优化: 基于真实指标）
        List<String> dynamic = generateDynamicItems(stage, outputs);
        checklist.addAll(dynamic);

        if (checklist.size() < properties.getMinItems()) {
            log.warn("Checklist for {} has only {} items (< minItems={}); consider configuring more",
                    stage.getCode(), checklist.size(), properties.getMinItems());
        }

        log.info("Generated checklist for {}: {} items ({} dynamic)", stage.getCode(),
                checklist.size(), dynamic.size());
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
     * 基于阶段输出动态追加检查项（P2 优化: 基于真实指标）。
     * <p>针对不同阶段检查不同的输出字段和指标：
     * <ul>
     *   <li>DATA_ANALYSIS：使用 {@link MetricsParser} 检测互动率、增长率等异常</li>
     *   <li>PUBLISHING：检查失败平台列表</li>
     *   <li>CONTENT_CREATION：检查篇幅、关键词覆盖</li>
     *   <li>TOPIC_PLANNING：检查热点关键词是否存在（修复空值判断 bug）</li>
     *   <li>OPTIMIZATION：检查是否包含可操作建议</li>
     *   <li>IMAGE_DESIGN：检查图片数量与平台适配</li>
     * </ul>
     */
    private List<String> generateDynamicItems(AgentStage stage, Map<String, Object> outputs) {
        List<String> dynamic = new ArrayList<>();
        if (outputs == null || outputs.isEmpty()) {
            return dynamic;
        }

        switch (stage) {
            case DATA_ANALYSIS -> generateAnalysisDynamicItems(outputs, dynamic);
            case PUBLISHING -> generatePublishingDynamicItems(outputs, dynamic);
            case CONTENT_CREATION -> generateContentDynamicItems(outputs, dynamic);
            case TOPIC_PLANNING -> generateTopicDynamicItems(outputs, dynamic);
            case OPTIMIZATION -> generateOptimizationDynamicItems(outputs, dynamic);
            case IMAGE_DESIGN -> generateImageDynamicItems(outputs, dynamic);
        }
        return dynamic;
    }

    /**
     * 数据分析阶段动态检查项（P2 优化: 基于 MetricsParser 真实指标）。
     */
    private void generateAnalysisDynamicItems(Map<String, Object> outputs, List<String> dynamic) {
        // 检查负向信号（原有逻辑）
        if (containsNegativeSignal(outputs)) {
            dynamic.add("【动态】检测到负向/异常指标，请人工优先排查原因并制定止损方案");
        }

        // P2 优化: 使用 MetricsParser 检测真实指标异常
        String analysisText = extractTextFromOutputs(outputs);
        if (!analysisText.isBlank()) {
            ParsedMetrics metrics = metricsParser.parse(analysisText);
            if (metrics.hasData()) {
                // 互动率低于阈值
                double engagementRate = metricsParser.computeEngagementRate(metrics);
                if (engagementRate > 0 && engagementRate < 0.03) {
                    dynamic.add(String.format(
                            "【动态】互动率仅 %.1f%%（低于3%%），建议人工排查内容质量与受众匹配度",
                            engagementRate * 100));
                }

                // 粉丝净增长为负
                if (metrics.netGrowth() < 0) {
                    dynamic.add(String.format(
                            "【动态】粉丝净增长为负（%d），请人工排查取关原因并制定挽留策略",
                            metrics.netGrowth()));
                }

                // 阅读完成率过低
                double finishRate = metricsParser.getReadFinishRate(metrics);
                if (finishRate > 0 && finishRate < 0.3) {
                    dynamic.add(String.format(
                            "【动态】阅读完成率仅 %.1f%%（低于30%%），建议优化内容开头吸引力",
                            finishRate * 100));
                }

                // 环比下降
                if (metrics.growthRate() < 0) {
                    dynamic.add(String.format(
                            "【动态】环比下降 %.1f%%，请人工分析下降原因并制定应对策略",
                            Math.abs(metrics.growthRate()) * 100));
                }
            } else if (analysisText.length() > 100) {
                // 内容较长但未解析到任何指标
                dynamic.add("【动态】分析内容未包含可识别的数值指标，请人工核对数据来源是否已接入平台后台");
            }
        }
    }

    /**
     * 发布阶段动态检查项。
     */
    private void generatePublishingDynamicItems(Map<String, Object> outputs, List<String> dynamic) {
        // 检查失败平台列表
        Object failedPlatforms = outputs.get("failedPlatforms");
        if (failedPlatforms != null) {
            dynamic.add("【动态】存在发布失败的平台：" + failedPlatforms + "，请人工核查账号状态并重试");
        }
        Object errors = outputs.get("errors");
        if (errors != null) {
            dynamic.add("【动态】发布过程出现错误：" + errors + "，请人工核查并处理");
        }

        // 检查发布状态
        Object status = outputs.get("publishStatus");
        if (status instanceof String s && (s.contains("fail") || s.contains("error"))) {
            dynamic.add("【动态】发布状态异常，请人工核查各平台发布结果");
        }
    }

    /**
     * 内容创作阶段动态检查项。
     */
    private void generateContentDynamicItems(Map<String, Object> outputs, List<String> dynamic) {
        Object wordCount = outputs.get("wordCount");
        if (wordCount instanceof Number n) {
            int wc = n.intValue();
            if (wc > 3000) {
                dynamic.add("【动态】初稿篇幅较长（" + wc + "字），建议人工评估是否拆分为系列内容");
            } else if (wc < 300 && wc > 0) {
                dynamic.add("【动态】初稿篇幅较短（" + wc + "字），建议人工补充细节与案例");
            }
        }

        // 检查是否包含个人化内容
        Object content = outputs.get("content");
        if (content instanceof String s) {
            boolean hasPersonalElement = s.contains("个人经历") || s.contains("案例")
                    || s.contains("故事") || s.contains("亲身");
            if (!hasPersonalElement) {
                dynamic.add("【动态】未检测到个人化内容标记，建议人工补充独家经历或案例");
            }
        }
    }

    /**
     * 选题阶段动态检查项（P2 修复: 空值判断 bug）。
     */
    private void generateTopicDynamicItems(Map<String, Object> outputs, List<String> dynamic) {
        // P2 修复: 原逻辑 outputs.get("trendingKeywords") == null 会在 key 存在但 value 为 null 时误判
        // 改为检查 key 是否存在，以及 value 是否为空
        if (!outputs.containsKey("trendingKeywords")
                || outputs.get("trendingKeywords") == null
                || (outputs.get("trendingKeywords") instanceof String s && s.isBlank())) {
            dynamic.add("【动态】未检索到热点关键词，请人工补充领域热点输入");
        }

        // 检查竞品分析是否完整
        if (!outputs.containsKey("competitorAnalysis") || outputs.get("competitorAnalysis") == null) {
            dynamic.add("【动态】缺少竞品分析数据，建议人工补充目标竞品信息");
        }
    }

    /**
     * 优化阶段动态检查项（P2 新增）。
     */
    private void generateOptimizationDynamicItems(Map<String, Object> outputs, List<String> dynamic) {
        // 检查是否包含可操作建议
        Object recommendations = outputs.get("recommendations");
        if (recommendations == null) {
            dynamic.add("【动态】优化建议为空，请人工补充具体的策略调整方向");
        } else if (recommendations instanceof List<?> list && list.isEmpty()) {
            dynamic.add("【动态】优化建议列表为空，请人工补充具体的策略调整方向");
        }

        // 检查是否包含回滚方案
        Object content = outputs.get("content");
        if (content instanceof String s && !s.contains("回滚") && !s.contains("rollback")) {
            dynamic.add("【动态】未检测到回滚方案，建议人工补充策略切换的回滚预案");
        }
    }

    /**
     * 配图设计阶段动态检查项（P2 新增）。
     */
    private void generateImageDynamicItems(Map<String, Object> outputs, List<String> dynamic) {
        // 检查图片数量
        Object imageCount = outputs.get("imageCount");
        if (imageCount instanceof Number n && n.intValue() < 3) {
            dynamic.add("【动态】配图数量较少（" + n.intValue() + "张），建议人工确认是否满足多平台需求");
        }

        // 检查平台适配
        if (!outputs.containsKey("platformAdapted") || outputs.get("platformAdapted") == null) {
            dynamic.add("【动态】缺少平台适配信息，请人工确认各平台封面尺寸是否已适配");
        }
    }

    /**
     * 从输出 Map 中提取文本内容（用于 MetricsParser 解析）。
     */
    private String extractTextFromOutputs(Map<String, Object> outputs) {
        StringBuilder sb = new StringBuilder();
        for (Object value : outputs.values()) {
            if (value instanceof String s) {
                sb.append(s).append("\n");
            } else if (value instanceof Map<?, ?> m) {
                extractTextFromMap(m, sb);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        sb.append(s).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private void extractTextFromMap(Map<?, ?> map, StringBuilder sb) {
        for (Object value : map.values()) {
            if (value instanceof String s) {
                sb.append(s).append("\n");
            } else if (value instanceof Map<?, ?> m) {
                extractTextFromMap(m, sb);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        sb.append(s).append("\n");
                    }
                }
            }
        }
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
