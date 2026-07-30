package com.contentops.common.llmops;

import com.contentops.common.prompt.PromptVersionProperties;
import com.contentops.common.prompt.PromptVersionService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prompt 版本控制服务。
 *
 * <p>在现有 {@link PromptVersionService}（负责版本选择与 A/B 变体决策）的基础上，
 * 增强以下能力，构建完整的 Prompt 工程治理体系：
 * <ul>
 *   <li><b>版本管理</b>：存储、查询、回滚、diff 比对</li>
 *   <li><b>A/B 测试</b>：流量分配、指标收集、显著性判断</li>
 *   <li><b>自动优化建议</b>：基于质量评分推荐优化方向</li>
 *   <li><b>模板继承与组合</b>：支持 Prompt 模板的父继承与片段组合</li>
 * </ul>
 *
 * <h3>版本管理</h3>
 * <p>每个 Prompt 以 {@code agentKey} 为命名空间，版本号按提交顺序自增。
 * {@link #saveVersion} 写入新版本，{@link #rollback} 回滚到指定版本，
 * {@link #diff} 对比两个版本的内容差异，{@link #getHistory} 查询变更历史。
 *
 * <h3>A/B 测试</h3>
 * <p>{@link #createExperiment} 创建实验，{@link #assignVariant} 按流量比例分配变体，
 * {@link #recordExperimentMetric} 收集指标，{@link #evaluateExperiment} 评估实验结果并给出推荐。
 *
 * <h3>自动优化建议</h3>
 * <p>{@link #suggestOptimization} 基于历史质量评分，分析薄弱维度并推荐优化方向
 * （如增强 few-shot 示例、调整指令措辞、增加约束条件等）。
 *
 * <h3>模板继承与组合</h3>
 * <p>{@link #registerTemplate} 注册模板并指定父模板，{@link #resolveTemplate} 解析时
 * 自动合并父模板内容（子覆盖父），{@link #composePrompt} 组合多个片段模板。
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>版本存储未初始化或异常时，{@link #getActiveVersion} 回退到 {@link PromptVersionService} 的配置版本</li>
 *   <li>实验评估样本不足时返回「数据不足」结论而非抛出异常</li>
 *   <li>模板解析失败时返回原始模板内容并记录警告</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 保存新版本
 * promptVersionControl.saveVersion("topic", "v3", "增加非常规角度引导", newPromptContent);
 *
 * // 回滚
 * promptVersionControl.rollback("topic", "v2");
 *
 * // 创建 A/B 实验
 * promptVersionControl.createExperiment("topic-ab-1", "topic", "v2", "v3", 50);
 * String variant = promptVersionControl.assignVariant("topic-ab-1", "memoryId-xxx");
 * promptVersionControl.recordExperimentMetric("topic-ab-1", variant, 85);
 * ExperimentEvaluation eval = promptVersionControl.evaluateExperiment("topic-ab-1");
 *
 * // 优化建议
 * List<OptimizationSuggestion> suggestions = promptVersionControl.suggestOptimization("topic");
 * }</pre>
 *
 * @see PromptVersionService
 * @see PromptVersionProperties
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptVersionControl {

    private final PromptVersionService promptVersionService;
    private final PromptVersionProperties promptVersionProperties;
    private final PromptControlProperties properties;

    /** 版本存储：agentKey -> (版本号 -> 版本记录) */
    private final Map<String, Map<String, PromptVersion>> versionStore = new ConcurrentHashMap<>();

    /** 各 agentKey 的当前激活版本 */
    private final Map<String, String> activeVersions = new ConcurrentHashMap<>();

    /** 版本自增计数器：agentKey -> 最新序号 */
    private final Map<String, AtomicInteger> versionSequences = new ConcurrentHashMap<>();

    /** 实验存储：experimentId -> 实验对象 */
    private final Map<String, AbExperiment> experiments = new ConcurrentHashMap<>();

    /** 模板存储：templateKey -> 模板定义 */
    private final Map<String, PromptTemplate> templateStore = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════
    //  版本管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 保存一个 Prompt 新版本。
     *
     * @param agentKey  Agent 标识（如 topic、content）
     * @param version   版本号（如 v3），为空时自动生成
     * @param changeLog 变更说明
     * @param content   Prompt 内容
     * @return 已保存的版本记录
     */
    public PromptVersion saveVersion(String agentKey, String version, String changeLog, String content) {
        Objects.requireNonNull(agentKey, "agentKey 不能为空");
        Objects.requireNonNull(content, "Prompt 内容不能为空");

        String versionNo = (version == null || version.isBlank())
                ? generateVersion(agentKey) : version;
        int seq = versionSequences.computeIfAbsent(agentKey, k -> new AtomicInteger(0))
                .incrementAndGet();

        PromptVersion pv = new PromptVersion(
                agentKey, versionNo, seq, content, changeLog, Instant.now(), true);
        versionStore.computeIfAbsent(agentKey, k -> new ConcurrentHashMap<>())
                .put(versionNo, pv);

        // 首次保存自动设为激活版本
        activeVersions.putIfAbsent(agentKey, versionNo);

        log.info("[PromptVersionControl] 保存版本 agentKey={}, version={}, seq={}, changeLog={}",
                agentKey, versionNo, seq, changeLog);
        return pv;
    }

    /**
     * 获取指定 agentKey 的当前激活版本内容。
     *
     * <p>降级策略：版本存储中无记录时，回退到 {@link PromptVersionService#getVersion} 的配置版本。
     *
     * @param agentKey Agent 标识
     * @return 激活版本的 Prompt 内容
     */
    public String getActiveVersion(String agentKey) {
        String activeVersion = activeVersions.get(agentKey);
        if (activeVersion == null) {
            // 降级：回退到 PromptVersionService 的配置版本
            String configuredVersion = promptVersionService.getVersion(agentKey);
            log.debug("[PromptVersionControl] agentKey={} 无存储版本, 回退到配置版本: {}",
                    agentKey, configuredVersion);
            return "[使用配置版本 " + configuredVersion + "] 请通过 PromptFragmentService 获取动态 Prompt";
        }
        PromptVersion pv = getVersion(agentKey, activeVersion);
        return pv != null ? pv.content() : "";
    }

    /**
     * 获取指定 agentKey 的当前激活版本号。
     *
     * @param agentKey Agent 标识
     * @return 激活版本号，无记录时返回 null
     */
    public String getActiveVersionNo(String agentKey) {
        return activeVersions.get(agentKey);
    }

    /**
     * 获取指定版本记录。
     *
     * @param agentKey Agent 标识
     * @param version  版本号
     * @return 版本记录，不存在时返回 null
     */
    public PromptVersion getVersion(String agentKey, String version) {
        Map<String, PromptVersion> versions = versionStore.get(agentKey);
        return versions == null ? null : versions.get(version);
    }

    /**
     * 获取指定 agentKey 的全部版本历史（按序号降序）。
     *
     * @param agentKey Agent 标识
     * @return 版本历史列表
     */
    public List<PromptVersion> getHistory(String agentKey) {
        Map<String, PromptVersion> versions = versionStore.get(agentKey);
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        return versions.values().stream()
                .sorted(Comparator.comparingInt(PromptVersion::sequence).reversed())
                .toList();
    }

    /**
     * 回滚到指定版本。
     *
     * <p>将激活版本切换为目标版本，并保留历史记录（不删除后续版本，仅切换激活指针）。
     *
     * @param agentKey Agent 标识
     * @param version  要回滚到的版本号
     * @return 回滚后的激活版本记录
     * @throws IllegalArgumentException 当目标版本不存在时
     */
    public PromptVersion rollback(String agentKey, String version) {
        PromptVersion target = getVersion(agentKey, version);
        if (target == null) {
            throw new IllegalArgumentException(
                    "回滚失败: agentKey=" + agentKey + " 不存在版本 " + version);
        }
        String previous = activeVersions.put(agentKey, version);
        log.info("[PromptVersionControl] 回滚 agentKey={}, {} -> {}", agentKey, previous, version);
        return target;
    }

    /**
     * 对比两个版本的内容差异（逐行 diff）。
     *
     * @param agentKey Agent 标识
     * @param versionA 版本 A
     * @param versionB 版本 B
     * @return 差异结果
     */
    public VersionDiff diff(String agentKey, String versionA, String versionB) {
        PromptVersion a = getVersion(agentKey, versionA);
        PromptVersion b = getVersion(agentKey, versionB);
        if (a == null || b == null) {
            throw new IllegalArgumentException("diff 失败: 版本不存在 A=" + versionA + " B=" + versionB);
        }
        return VersionDiff.builder()
                .agentKey(agentKey)
                .versionA(versionA)
                .versionB(versionB)
                .diffLines(computeLineDiff(a.content(), b.content()))
                .summary("版本 " + versionA + " 与 " + versionB + " 的逐行差异")
                .build();
    }

    // ════════════════════════════════════════════════════════════════
    //  A/B 测试
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建一个 A/B 测试实验。
     *
     * @param experimentId 实验 ID
     * @param agentKey     Agent 标识
     * @param variantA     变体 A 使用的版本号
     * @param variantB     变体 B 使用的版本号
     * @param trafficSplit 变体 A 的流量百分比（0-100）
     * @return 实验对象
     */
    public AbExperiment createExperiment(String experimentId, String agentKey,
                                          String variantA, String variantB, int trafficSplit) {
        Objects.requireNonNull(experimentId, "experimentId 不能为空");
        if (variantA.equals(variantB)) {
            throw new IllegalArgumentException("变体 A 与 B 不能相同");
        }
        int split = Math.max(0, Math.min(100, trafficSplit));
        AbExperiment experiment = new AbExperiment(
                experimentId, agentKey, variantA, variantB, split, Instant.now(),
                AbExperimentStatus.RUNNING,
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        experiments.put(experimentId, experiment);
        log.info("[PromptVersionControl] 创建实验 id={}, agentKey={}, A={}, B={}, split={}%",
                experimentId, agentKey, variantA, variantB, split);
        return experiment;
    }

    /**
     * 为请求分配 A/B 实验变体。
     *
     * <p>基于 memoryId 哈希与流量比例分配，保证同一 memoryId 获得一致变体。
     *
     * @param experimentId 实验 ID
     * @param memoryId     对话记忆 ID（哈希种子）
     * @return 变体标识（"A" 或 "B"）
     */
    public String assignVariant(String experimentId, String memoryId) {
        AbExperiment experiment = experiments.get(experimentId);
        if (experiment == null) {
            throw new IllegalArgumentException("实验不存在: " + experimentId);
        }
        if (experiment.status() != AbExperimentStatus.RUNNING) {
            throw new IllegalStateException("实验未运行: " + experimentId);
        }
        int hash = Math.abs((experimentId + ":" + memoryId).hashCode()) % 100;
        String variant = hash < experiment.trafficSplit() ? "A" : "B";
        experiment.variantCounts()
                .computeIfAbsent(variant, k -> new AtomicInteger(0))
                .incrementAndGet();
        return variant;
    }

    /**
     * 记录一次实验指标样本。
     *
     * @param experimentId 实验 ID
     * @param variant      变体标识
     * @param score        质量评分（0-100）
     */
    public void recordExperimentMetric(String experimentId, String variant, double score) {
        AbExperiment experiment = experiments.get(experimentId);
        if (experiment == null) {
            log.warn("[PromptVersionControl] 记录指标失败: 实验不存在 {}", experimentId);
            return;
        }
        experiment.metrics().compute(variant, (k, v) -> {
            List<Double> list = v == null ? Collections.synchronizedList(new ArrayList<>()) : v;
            list.add(score);
            return list;
        });
    }

    /**
     * 评估实验结果。
     *
     * <p>对变体 A/B 的指标样本进行均值对比与简化 T 检验，给出推荐结论。
     * 样本不足时返回「数据不足」结论。
     *
     * @param experimentId 实验 ID
     * @return 评估结果
     */
    public ExperimentEvaluation evaluateExperiment(String experimentId) {
        AbExperiment experiment = experiments.get(experimentId);
        if (experiment == null) {
            throw new IllegalArgumentException("实验不存在: " + experimentId);
        }
        List<Double> a = experiment.metrics().getOrDefault("A", List.of());
        List<Double> b = experiment.metrics().getOrDefault("B", List.of());

        int minSamples = properties.getMinSamplesForEvaluation();
        if (a.size() < minSamples || b.size() < minSamples) {
            return ExperimentEvaluation.builder()
                    .experimentId(experimentId)
                    .variantASampleCount(a.size())
                    .variantBSampleCount(b.size())
                    .conclusion("数据不足: 变体A样本=" + a.size() + " 变体B样本=" + b.size()
                            + " (需各≥" + minSamples + ")")
                    .recommendation("继续收集样本")
                    .build();
        }

        double meanA = mean(a);
        double meanB = mean(b);
        double stdA = stddev(a, meanA);
        double stdB = stddev(b, meanB);
        // 简化 Welch's t 统计量
        double t = computeTStatistic(meanA, meanB, stdA, stdB, a.size(), b.size());
        boolean significant = Math.abs(t) > properties.getSignificanceThreshold();
        String winner = meanA > meanB ? "A" : "B";
        String conclusion = significant
                ? "变体 " + winner + " 显著优于另一变体 (t=" + String.format("%.3f", t) + ")"
                : "两变体差异不显著 (t=" + String.format("%.3f", t) + ")";
        String recommendation = significant
                ? "建议将变体 " + winner + " 设为默认版本（版本 " + (winner.equals("A") ? experiment.variantA() : experiment.variantB()) + "）"
                : "建议延长实验周期或调整变量后再测";

        return ExperimentEvaluation.builder()
                .experimentId(experimentId)
                .variantAMean(meanA)
                .variantBMean(meanB)
                .variantAStddev(stdA)
                .variantBStddev(stdB)
                .variantASampleCount(a.size())
                .variantBSampleCount(b.size())
                .tStatistic(t)
                .significant(significant)
                .winnerVariant(significant ? winner : null)
                .conclusion(conclusion)
                .recommendation(recommendation)
                .build();
    }

    /**
     * 获取指定 agentKey 上的全部实验。
     *
     * @param agentKey Agent 标识
     * @return 实验列表
     */
    public List<AbExperiment> getExperiments(String agentKey) {
        return experiments.values().stream()
                .filter(e -> e.agentKey().equals(agentKey))
                .toList();
    }

    /**
     * 终止实验。
     *
     * @param experimentId 实验 ID
     */
    public void stopExperiment(String experimentId) {
        AbExperiment experiment = experiments.get(experimentId);
        if (experiment != null) {
            experiments.put(experimentId, new AbExperiment(
                    experiment.id(), experiment.agentKey(), experiment.variantA(),
                    experiment.variantB(), experiment.trafficSplit(), experiment.createdAt(),
                    AbExperimentStatus.STOPPED, experiment.variantCounts(), experiment.metrics()));
            log.info("[PromptVersionControl] 终止实验 {}", experimentId);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  自动优化建议
    // ════════════════════════════════════════════════════════════════

    /**
     * 基于历史质量评分推荐 Prompt 优化方向。
     *
     * <p>分析规则：
     * <ul>
     *   <li>平均分低于阈值 → 推荐「整体重写」</li>
     *   <li>评分方差大 → 推荐「增加稳定性约束（few-shot、格式约束）」</li>
     *   <li>近 5 版评分持续下降 → 推荐「回滚到历史最优版本」</li>
     *   <li>评分波动但无趋势 → 推荐「A/B 测试验证改进方向」</li>
     * </ul>
     *
     * @param agentKey Agent 标识
     * @return 优化建议列表
     */
    public List<OptimizationSuggestion> suggestOptimization(String agentKey) {
        List<OptimizationSuggestion> suggestions = new ArrayList<>();
        // 收集该 agentKey 关联实验的评分样本
        List<Double> allScores = new ArrayList<>();
        experiments.values().stream()
                .filter(e -> e.agentKey().equals(agentKey))
                .forEach(e -> {
                    allScores.addAll(e.metrics().getOrDefault("A", List.of()));
                    allScores.addAll(e.metrics().getOrDefault("B", List.of()));
                });

        if (allScores.isEmpty()) {
            suggestions.add(OptimizationSuggestion.builder()
                    .type(SuggestionType.COLLECT_DATA)
                    .priority(1)
                    .description("暂无足够质量评分数据，建议先运行 A/B 测试收集样本")
                    .action("创建实验并调用 recordExperimentMetric 收集至少 "
                            + properties.getMinSamplesForEvaluation() + " 个样本")
                    .build());
            return suggestions;
        }

        double mean = mean(allScores);
        double std = stddev(allScores, mean);

        // 规则 1：平均分低
        if (mean < properties.getLowScoreThreshold()) {
            suggestions.add(OptimizationSuggestion.builder()
                    .type(SuggestionType.REWRITE)
                    .priority(1)
                    .description("平均质量评分偏低（" + String.format("%.1f", mean) + "），整体表达需改进")
                    .action("重写系统提示词，明确角色定位、输出格式与质量要求，增加高质量 few-shot 示例")
                    .build());
        }

        // 规则 2：方差大（稳定性差）
        if (std > properties.getHighVarianceThreshold()) {
            suggestions.add(OptimizationSuggestion.builder()
                    .type(SuggestionType.STABILIZE)
                    .priority(2)
                    .description("评分波动较大（标准差 " + String.format("%.1f", std) + "），输出稳定性不足")
                    .action("增加输出格式约束、增加 few-shot 示例数量、降低采样温度，减少随机性")
                    .build());
        }

        // 规则 3：近 5 版评分趋势
        List<PromptVersion> history = getHistory(agentKey);
        if (history.size() >= 3) {
            List<Double> recent = new ArrayList<>();
            history.stream().limit(5).forEach(v -> {
                // 取该版本在实验中的平均分作为近似
                List<Double> scores = new ArrayList<>();
                experiments.values().stream()
                        .filter(e -> e.agentKey().equals(agentKey))
                        .forEach(e -> {
                            if (e.variantA().equals(v.version())) {
                                scores.addAll(e.metrics().getOrDefault("A", List.of()));
                            }
                            if (e.variantB().equals(v.version())) {
                                scores.addAll(e.metrics().getOrDefault("B", List.of()));
                            }
                        });
                if (!scores.isEmpty()) {
                    recent.add(mean(scores));
                }
            });
            if (recent.size() >= 3 && isDeclining(recent)) {
                suggestions.add(OptimizationSuggestion.builder()
                        .type(SuggestionType.ROLLBACK)
                        .priority(1)
                        .description("近期版本评分持续下降，新版本可能引入了回归")
                        .action("回滚到历史最优版本，并重新评估改进方向")
                        .build());
            }
        }

        // 规则 4：评分尚可但有改进空间
        if (suggestions.isEmpty() && mean < properties.getGoodScoreThreshold()) {
            suggestions.add(OptimizationSuggestion.builder()
                    .type(SuggestionType.AB_TEST)
                    .priority(3)
                    .description("评分中等（" + String.format("%.1f", mean) + "），存在提升空间")
                    .action("设计 A/B 实验验证具体改进方向（如调整指令措辞、增加约束）")
                    .build());
        }

        if (suggestions.isEmpty()) {
            suggestions.add(OptimizationSuggestion.builder()
                    .type(SuggestionType.MAINTAIN)
                    .priority(5)
                    .description("当前 Prompt 质量良好（均值 " + String.format("%.1f", mean) + "），建议保持")
                    .action("无需立即调整，持续监控质量趋势")
                    .build());
        }

        return suggestions;
    }

    // ════════════════════════════════════════════════════════════════
    //  模板继承与组合
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册一个 Prompt 模板。
     *
     * @param templateKey   模板键
     * @param content       模板内容（可含变量占位符 {{var}}）
     * @param parentKey     父模板键（为空表示无父模板）
     * @param description   模板描述
     * @return 已注册的模板
     */
    public PromptTemplate registerTemplate(String templateKey, String content,
                                            String parentKey, String description) {
        Objects.requireNonNull(templateKey, "templateKey 不能为空");
        Objects.requireNonNull(content, "模板内容不能为空");
        PromptTemplate template = new PromptTemplate(templateKey, content, parentKey, description, Instant.now());
        templateStore.put(templateKey, template);
        log.info("[PromptVersionControl] 注册模板 key={}, parent={}", templateKey, parentKey);
        return template;
    }

    /**
     * 解析模板（含父模板继承合并）。
     *
     * <p>合并规则：父模板内容在前，子模板内容在后；子模板可通过 {@code {{override}}}
     * 占位完全覆盖父内容。循环继承会被检测并抛出异常。
     *
     * @param templateKey 模板键
     * @return 解析后的完整模板内容
     */
    public String resolveTemplate(String templateKey) {
        return resolveTemplate(templateKey, new java.util.HashSet<>());
    }

    /**
     * 递归解析模板（带循环检测）。
     */
    private String resolveTemplate(String templateKey, java.util.Set<String> visited) {
        if (visited.contains(templateKey)) {
            throw new IllegalStateException("检测到模板循环继承: " + templateKey);
        }
        PromptTemplate template = templateStore.get(templateKey);
        if (template == null) {
            log.warn("[PromptVersionControl] 模板不存在: {}", templateKey);
            return "[模板不存在: " + templateKey + "]";
        }
        visited.add(templateKey);
        // 无父模板，直接返回
        if (template.parentKey() == null || template.parentKey().isBlank()) {
            return template.content();
        }
        // 合并父模板
        try {
            String parentContent = resolveTemplate(template.parentKey(), visited);
            // 子模板含 override 标记则完全覆盖父内容
            if (template.content().contains("{{override}}")) {
                return template.content().replace("{{override}}", "");
            }
            return parentContent + "\n\n" + template.content();
        } catch (Exception e) {
            log.warn("[PromptVersionControl] 解析父模板失败, 返回子模板内容: {}, 错误: {}",
                    templateKey, e.getMessage());
            return template.content();
        }
    }

    /**
     * 组合多个片段模板为一个完整 Prompt。
     *
     * @param templateKeys 模板键列表（按顺序拼接）
     * @return 组合后的完整 Prompt
     */
    public String composePrompt(List<String> templateKeys) {
        if (templateKeys == null || templateKeys.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : templateKeys) {
            String resolved = resolveTemplate(key);
            if (sb.length() > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append(resolved);
        }
        return sb.toString();
    }

    /**
     * 使用变量填充模板（替换 {{var}} 占位符）。
     *
     * @param templateKey 模板键
     * @param variables   变量键值对
     * @return 填充后的 Prompt
     */
    public String renderTemplate(String templateKey, Map<String, String> variables) {
        String content = resolveTemplate(templateKey);
        if (variables == null || variables.isEmpty()) {
            return content;
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            content = content.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return content;
    }

    /**
     * 获取已注册的全部模板。
     *
     * @return 模板列表
     */
    public List<PromptTemplate> listTemplates() {
        return new ArrayList<>(templateStore.values());
    }

    // ════════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 自动生成版本号。
     */
    private String generateVersion(String agentKey) {
        int seq = versionSequences.computeIfAbsent(agentKey, k -> new AtomicInteger(0))
                .incrementAndGet();
        return "v" + seq;
    }

    /**
     * 计算两个文本的逐行 diff。
     */
    private List<DiffLine> computeLineDiff(String textA, String textB) {
        String[] linesA = textA.split("\n", -1);
        String[] linesB = textB.split("\n", -1);
        List<DiffLine> diffLines = new ArrayList<>();
        int max = Math.max(linesA.length, linesB.length);
        for (int i = 0; i < max; i++) {
            String a = i < linesA.length ? linesA[i] : null;
            String b = i < linesB.length ? linesB[i] : null;
            if (Objects.equals(a, b)) {
                continue;
            }
            DiffLineType type;
            if (a == null) {
                type = DiffLineType.ADDED;
            } else if (b == null) {
                type = DiffLineType.REMOVED;
            } else {
                type = DiffLineType.MODIFIED;
            }
            diffLines.add(new DiffLine(i + 1, type, a, b));
        }
        return diffLines;
    }

    /**
     * 计算列表均值。
     */
    private double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * 计算样本标准差。
     */
    private double stddev(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum() / (values.size() - 1);
        return Math.sqrt(variance);
    }

    /**
     * 计算 Welch's t 统计量。
     */
    private double computeTStatistic(double meanA, double meanB,
                                      double stdA, double stdB,
                                      int nA, int nB) {
        double denom = Math.sqrt(stdA * stdA / nA + stdB * stdB / nB);
        if (denom == 0) {
            return 0.0;
        }
        return (meanA - meanB) / denom;
    }

    /**
     * 判断评分序列是否持续下降。
     */
    private boolean isDeclining(List<Double> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) >= values.get(i - 1)) {
                return false;
            }
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  数据结构定义
    // ════════════════════════════════════════════════════════════════

    /**
     * Prompt 版本记录。
     *
     * @param agentKey  Agent 标识
     * @param version   版本号
     * @param sequence  自增序号
     * @param content   Prompt 内容
     * @param changeLog 变更说明
     * @param createdAt 创建时间
     * @param active    是否为激活版本
     */
    public record PromptVersion(String agentKey, String version, int sequence,
                                 String content, String changeLog,
                                 Instant createdAt, boolean active) {
    }

    /**
     * A/B 测试实验。
     *
     * @param id            实验 ID
     * @param agentKey      Agent 标识
     * @param variantA      变体 A 版本号
     * @param variantB      变体 B 版本号
     * @param trafficSplit  变体 A 流量百分比（0-100）
     * @param createdAt     创建时间
     * @param status        实验状态
     * @param variantCounts 各变体分配次数
     * @param metrics       各变体指标样本
     */
    public record AbExperiment(String id, String agentKey, String variantA, String variantB,
                                int trafficSplit, Instant createdAt, AbExperimentStatus status,
                                Map<String, AtomicInteger> variantCounts,
                                Map<String, List<Double>> metrics) {
    }

    /** 实验状态 */
    public enum AbExperimentStatus {
        /** 运行中 */
        RUNNING,
        /** 已停止 */
        STOPPED,
        /** 已完成 */
        COMPLETED
    }

    /**
     * 版本 diff 结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VersionDiff {
        /** Agent 标识 */
        private String agentKey;
        /** 版本 A */
        private String versionA;
        /** 版本 B */
        private String versionB;
        /** 差异行列表 */
        private List<DiffLine> diffLines;
        /** 摘要 */
        private String summary;
    }

    /**
     * 单行 diff 记录。
     *
     * @param lineNumber 行号（从 1 开始）
     * @param type       差异类型
     * @param lineA      版本 A 的行内容（null 表示新增行）
     * @param lineB      版本 B 的行内容（null 表示删除行）
     */
    public record DiffLine(int lineNumber, DiffLineType type, String lineA, String lineB) {
    }

    /** 差异行类型 */
    public enum DiffLineType {
        /** 新增行 */
        ADDED,
        /** 删除行 */
        REMOVED,
        /** 修改行 */
        MODIFIED
    }

    /**
     * 实验评估结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExperimentEvaluation {
        /** 实验 ID */
        private String experimentId;
        /** 变体 A 均值 */
        private double variantAMean;
        /** 变体 B 均值 */
        private double variantBMean;
        /** 变体 A 标准差 */
        private double variantAStddev;
        /** 变体 B 标准差 */
        private double variantBStddev;
        /** 变体 A 样本数 */
        private int variantASampleCount;
        /** 变体 B 样本数 */
        private int variantBSampleCount;
        /** t 统计量 */
        private double tStatistic;
        /** 是否显著 */
        private boolean significant;
        /** 胜出变体（不显著时为 null） */
        private String winnerVariant;
        /** 结论说明 */
        private String conclusion;
        /** 推荐操作 */
        private String recommendation;
    }

    /**
     * 优化建议。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptimizationSuggestion {
        /** 建议类型 */
        private SuggestionType type;
        /** 优先级（1 最高） */
        private int priority;
        /** 描述 */
        private String description;
        /** 具体行动 */
        private String action;
    }

    /** 优化建议类型 */
    public enum SuggestionType {
        /** 重写 */
        REWRITE,
        /** 增强稳定性 */
        STABILIZE,
        /** 回滚 */
        ROLLBACK,
        /** A/B 测试验证 */
        AB_TEST,
        /** 收集数据 */
        COLLECT_DATA,
        /** 保持现状 */
        MAINTAIN
    }

    /**
     * Prompt 模板定义。
     *
     * @param key         模板键
     * @param content     模板内容
     * @param parentKey   父模板键
     * @param description 模板描述
     * @param createdAt   创建时间
     */
    public record PromptTemplate(String key, String content, String parentKey,
                                  String description, Instant createdAt) {
    }

    // ════════════════════════════════════════════════════════════════
    //  配置属性
    // ════════════════════════════════════════════════════════════════

    /**
     * Prompt 版本控制配置属性。
     *
     * <p>通过 {@code contentops.llmops.prompt-control.*} 在 application.yml 中绑定。
     *
     * <h3>配置示例</h3>
     * <pre>{@code
     * contentops:
     *   llmops:
     *     prompt-control:
     *       enabled: true
     *       min-samples-for-evaluation: 30
     *       significance-threshold: 1.96
     *       low-score-threshold: 60
     *       good-score-threshold: 85
     *       high-variance-threshold: 15.0
     *       max-version-history: 50
     * }</pre>
     */
    @Data
    @org.springframework.stereotype.Component
    @ConfigurationProperties(prefix = "contentops.llmops.prompt-control")
    public static class PromptControlProperties {

        /** 是否启用增强的 Prompt 版本控制（关闭时仅使用 PromptVersionService） */
        private boolean enabled = true;

        /** 实验评估所需的最小样本数（每个变体） */
        private int minSamplesForEvaluation = 30;

        /** t 检验显著性阈值（双侧，1.96 对应 α=0.05） */
        private double significanceThreshold = 1.96;

        /** 低分阈值（低于此值触发重写建议） */
        private int lowScoreThreshold = 60;

        /** 优良分数阈值（高于此值认为质量良好） */
        private int goodScoreThreshold = 85;

        /** 高方差阈值（标准差超过此值触发稳定性建议） */
        private double highVarianceThreshold = 15.0;

        /** 每个 agentKey 保留的最大版本历史数 */
        private int maxVersionHistory = 50;
    }
}
