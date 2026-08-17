package com.contentops.common.quality;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.platform.MetricsParser;
import com.contentops.common.platform.MetricsParser.ParsedMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 质量评估服务（P2 v2.1.0: 多模型路由与质量评估，P2 优化: 真实指标 + 阶段差异化 + 权重配置化）。
 *
 * <p>对每个 Agent 阶段的输出进行三维启发式评分，不依赖 LLM 调用，
 * 通过内容长度、结构完整性、关键词多样性等规则快速评估质量。
 *
 * <h3>三维评分维度</h3>
 * <ul>
 *   <li><b>逻辑性 (logic)</b>：基于标题层级、列表结构、逻辑连接词使用、段落均衡性</li>
 *   <li><b>可读性 (readability)</b>：基于内容长度、Markdown 格式化程度、段落分布</li>
 *   <li><b>原创性 (originality)</b>：基于中文汉字多样性、英文词汇丰富度、重复短语检测</li>
 * </ul>
 *
 * <h3>P2 优化改进</h3>
 * <ul>
 *   <li><b>权重配置化</b>：三维权重从硬编码改为通过 {@link QualityThresholdProperties} 配置，支持阶段差异化</li>
 *   <li><b>阶段差异化评分</b>：不同阶段在可读性和原创性维度采用差异化策略</li>
 *   <li><b>真实指标集成</b>：DATA_ANALYSIS 阶段使用 {@link MetricsParser} 校验数据质量</li>
 * </ul>
 *
 * <p>评分采用「基础分 + 增量项 - 扣分项」的方式，最终分数限制在 0-100 范围内。
 * 总分为三个维度的加权平均（权重可通过配置文件按阶段自定义）。
 *
 * @see QualityScore
 * @see QualityThresholdProperties
 * @see MetricsParser
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityAssessmentService {

    private final QualityThresholdProperties qualityProperties;
    private final MetricsParser metricsParser;

    // ──────────────── 逻辑连接词 ────────────────
    private static final String[] LOGICAL_CONNECTORS = {
            "首先", "其次", "然后", "最后", "因此", "所以", "总之",
            "然而", "但是", "另外", "此外", "综上", "换言之", "例如",
            "一方面", "另一方面", "与此同时", "不仅如此", "归根结底"
    };

    /** 选题阶段逻辑性加分关键词 */
    private static final String[] TOPIC_LOGIC_KEYWORDS = {
            "竞品", "受众", "差异化", "定位", "目标", "竞品分析", "受众画像"
    };

    /** 内容创作可读性加分关键词 */
    private static final String[] CONTENT_READABILITY_KEYWORDS = {
            "个人经历", "案例", "故事", "亲身", "实际", "经验", "感悟"
    };

    /** 数据分析逻辑性加分关键词 */
    private static final String[] ANALYSIS_LOGIC_KEYWORDS = {
            "环比", "同比", "趋势", "月度", "增长", "下降", "波动", "对比"
    };

    /** 优化建议可操作性加分关键词 */
    private static final String[] OPTIMIZATION_KEYWORDS = {
            "建议", "策略", "调整", "优化", "灰度", "A/B", "回滚", "试点"
    };

    // ──────────────── 正则模式（预编译提升性能） ────────────────
    /** Markdown 标题行：以 1-6 个 # 开头 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+.+");
    /** Markdown 列表行：以 -、*、+ 或 数字. 开头 */
    private static final Pattern LIST_PATTERN = Pattern.compile("(?m)^\\s*([-*+]|\\d+\\.)\\s+.+");
    /** Markdown 粗体文本：**text** */
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*[^*]+\\*\\*");
    /** Markdown 代码块：```...``` */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[\\s\\S]*?```");
    /** Markdown 引用块：以 > 开头 */
    private static final Pattern QUOTE_PATTERN = Pattern.compile("(?m)^>\\s+.+");
    /** 空行分隔的段落 */
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("\\n\\s*\\n");
    /** 重复短语检测：连续重复 2 次以上的 5+ 字符序列 */
    private static final Pattern REPEAT_PHRASE_PATTERN = Pattern.compile("(.{5,})\\1{1,}");
    /** 数值模式：用于检测数据分析阶段是否包含真实数字 */
    private static final Pattern NUMERIC_DATA_PATTERN = Pattern.compile("\\d+(?:[.,]\\d+)*\\s*(?:%|万|千|w|k|次|人|篇)?");

    /**
     * 对指定阶段的输出内容进行三维质量评分。
     *
     * @param stage   Agent 阶段（不同阶段采用不同评分策略）
     * @param content 待评估的内容文本
     * @return 质量评分结果，包含三维分数、总分和改进建议
     */
    public QualityScore assessQuality(AgentStage stage, String content) {
        // 空内容直接返回零分
        if (content == null || content.isBlank()) {
            log.warn("[QualityAssessment] stage={} 内容为空，返回零分", stage.getCode());
            return QualityScore.builder()
                    .logic(0)
                    .readability(0)
                    .originality(0)
                    .totalScore(0)
                    .suggestions(List.of("内容为空，请重新生成"))
                    .build();
        }

        // 获取阶段差异化权重
        QualityThresholdProperties.Weights weights = qualityProperties.getWeightsForStage(stage.getCode());

        // 计算三维评分（含阶段差异化调整）
        int logicScore = scoreLogic(content, stage);
        int readabilityScore = scoreReadability(content, stage);
        int originalityScore = scoreOriginality(content, stage);

        // 使用配置化权重计算加权总分（归一化后确保权重和为 1）
        int totalScore = (int) Math.round(
                logicScore * weights.normalizedLogic()
                        + readabilityScore * weights.normalizedReadability()
                        + originalityScore * weights.normalizedOriginality()
        );

        // 生成改进建议
        List<String> suggestions = generateSuggestions(stage, content,
                logicScore, readabilityScore, originalityScore);

        QualityScore score = QualityScore.builder()
                .logic(logicScore)
                .readability(readabilityScore)
                .originality(originalityScore)
                .totalScore(totalScore)
                .suggestions(suggestions)
                .build();

        log.info("[QualityAssessment] stage={}, logic={}, readability={}, originality={}, total={}, weights=[{},{},{}], suggestions={}",
                stage.getCode(), logicScore, readabilityScore, originalityScore, totalScore,
                weights.normalizedLogic(), weights.normalizedReadability(), weights.normalizedOriginality(),
                suggestions.size());

        return score;
    }

    // ──────────────── 逻辑性评分 ────────────────

    /**
     * 评估内容的逻辑性（含阶段差异化调整）。
     *
     * <p>基础评分依据：
     * <ul>
     *   <li>标题层级结构（# ## ###）— 最多 +20 分</li>
     *   <li>列表结构（有序/无序）— 最多 +15 分</li>
     *   <li>逻辑连接词使用 — 最多 +15 分</li>
     *   <li>基础分 50 分</li>
     * </ul>
     *
     * <p>阶段差异化加分：
     * <ul>
     *   <li>TOPIC_PLANNING：含竞品/受众/差异化关键词 +5~10</li>
     *   <li>DATA_ANALYSIS：含环比/同比/趋势关键词 +5~10</li>
     *   <li>OPTIMIZATION：含策略/建议/灰度关键词 +5~10</li>
     * </ul>
     */
    private int scoreLogic(String content, AgentStage stage) {
        int score = 50;

        // 标题结构
        int headingCount = countMatches(HEADING_PATTERN, content);
        score += Math.min(headingCount * 5, 20);

        // 列表结构
        int listCount = countMatches(LIST_PATTERN, content);
        score += Math.min(listCount * 3, 15);

        // 逻辑连接词
        int connectorCount = 0;
        for (String connector : LOGICAL_CONNECTORS) {
            connectorCount += countOccurrences(content, connector);
        }
        score += Math.min(connectorCount * 3, 15);

        // 阶段差异化加分
        score += stageLogicBonus(content, stage);

        return clamp(score, 0, 100);
    }

    /**
     * 阶段差异化逻辑性加分。
     */
    private int stageLogicBonus(String content, AgentStage stage) {
        return switch (stage) {
            case TOPIC_PLANNING -> keywordBonus(content, TOPIC_LOGIC_KEYWORDS, 5, 10);
            case DATA_ANALYSIS -> keywordBonus(content, ANALYSIS_LOGIC_KEYWORDS, 5, 10);
            case OPTIMIZATION -> keywordBonus(content, OPTIMIZATION_KEYWORDS, 5, 10);
            default -> 0;
        };
    }

    // ──────────────── 可读性评分 ────────────────

    /**
     * 评估内容的可读性（含阶段差异化调整）。
     *
     * <p>基础评分依据：
     * <ul>
     *   <li>内容长度（500-5000 字符为最佳区间）— 最多 +20 分</li>
     *   <li>Markdown 格式化（粗体、代码块、引用）— 最多 +15 分</li>
     *   <li>段落分布（空行分隔）— 最多 +15 分</li>
     *   <li>基础分 50 分</li>
     * </ul>
     *
     * <p>阶段差异化加分：
     * <ul>
     *   <li>CONTENT_CREATION：含个人经历/案例/故事关键词 +5~10</li>
     *   <li>DATA_ANALYSIS：含真实数值数据（通过 {@link MetricsParser} 检测）+5~10</li>
     * </ul>
     */
    private int scoreReadability(String content, AgentStage stage) {
        int score = 50;
        int length = content.length();

        // 内容长度评分
        if (length >= 500 && length <= 5000) {
            score += 20;
        } else if (length >= 200) {
            score += 10;
        } else if (length < 50) {
            score -= 15;
        }

        // Markdown 格式化评分
        int boldCount = countMatches(BOLD_PATTERN, content);
        int codeCount = countMatches(CODE_BLOCK_PATTERN, content);
        int quoteCount = countMatches(QUOTE_PATTERN, content);
        score += Math.min((boldCount + codeCount + quoteCount) * 3, 15);

        // 段落分布评分
        int paragraphCount = countMatches(PARAGRAPH_PATTERN, content);
        if (paragraphCount >= 5) {
            score += 15;
        } else if (paragraphCount >= 3) {
            score += 10;
        } else if (paragraphCount >= 1) {
            score += 5;
        }

        // 阶段差异化加分
        score += stageReadabilityBonus(content, stage);

        return clamp(score, 0, 100);
    }

    /**
     * 阶段差异化可读性加分。
     */
    private int stageReadabilityBonus(String content, AgentStage stage) {
        return switch (stage) {
            case CONTENT_CREATION -> keywordBonus(content, CONTENT_READABILITY_KEYWORDS, 5, 10);
            case DATA_ANALYSIS -> dataQualityBonus(content);
            default -> 0;
        };
    }

    /**
     * 数据分析阶段的数据质量加分。
     * <p>使用 {@link MetricsParser} 检测内容是否包含真实数值数据，
     * 若解析出有效指标则加分，鼓励数据驱动的分析输出。
     */
    private int dataQualityBonus(String content) {
        ParsedMetrics metrics = metricsParser.parse(content);
        if (!metrics.hasData()) {
            // 没有解析出任何数值数据，扣分
            return -5;
        }
        int bonus = 0;
        if (metrics.readCount() > 0 || metrics.playCount() > 0) bonus += 3;
        if (metrics.hasEngagementData()) bonus += 3;
        if (metrics.engagementRate() > 0 || metrics.readFinishRate() > 0) bonus += 4;
        return Math.min(bonus, 10);
    }

    // ──────────────── 原创性评分 ────────────────

    /**
     * 评估内容的原创性（含阶段差异化调整）。
     *
     * <p>基础评分依据：
     * <ul>
     *   <li>中文汉字多样性（唯一字 / 总字数）— 最多 +25 分</li>
     *   <li>英文词汇丰富度（唯一词 / 总词数）— 最多 +15 分</li>
     *   <li>重复短语扣分 — 最多 -20 分</li>
     *   <li>基础分 50 分</li>
     * </ul>
     *
     * <p>阶段差异化调整：
     * <ul>
     *   <li>DATA_ANALYSIS：数值数据丰富度加分（独立观点源于数据洞察）</li>
     * </ul>
     */
    private int scoreOriginality(String content, AgentStage stage) {
        int score = 50;

        // 中文汉字多样性
        String chineseChars = content.replaceAll("[^\\u4e00-\\u9fa5]", "");
        if (!chineseChars.isEmpty()) {
            Set<Character> uniqueChars = new HashSet<>();
            for (char c : chineseChars.toCharArray()) {
                uniqueChars.add(c);
            }
            double charDiversity = (double) uniqueChars.size() / chineseChars.length();
            if (charDiversity > 0.6) {
                score += 25;
            } else if (charDiversity > 0.4) {
                score += 15;
            } else if (charDiversity > 0.2) {
                score += 5;
            } else {
                score -= 10;
            }
        }

        // 英文词汇丰富度
        String[] englishWords = content.replaceAll("[^a-zA-Z\\s]", " ").trim().split("\\s+");
        Set<String> uniqueEnglish = new HashSet<>();
        int englishTotal = 0;
        for (String word : englishWords) {
            if (word.length() > 1) {
                uniqueEnglish.add(word.toLowerCase());
                englishTotal++;
            }
        }
        if (englishTotal > 0) {
            double engDiversity = (double) uniqueEnglish.size() / englishTotal;
            if (engDiversity > 0.7) {
                score += 15;
            } else if (engDiversity > 0.5) {
                score += 8;
            }
        }

        // 重复短语扣分
        int repeatCount = countMatches(REPEAT_PHRASE_PATTERN, content);
        score -= Math.min(repeatCount * 10, 20);

        // 阶段差异化调整
        if (stage == AgentStage.DATA_ANALYSIS) {
            // 数据分析阶段的原创性体现为数据洞察的丰富度
            int numericDataCount = countMatches(NUMERIC_DATA_PATTERN, content);
            score += Math.min(numericDataCount, 10);
        }

        return clamp(score, 0, 100);
    }

    // ──────────────── 改进建议生成 ────────────────

    /**
     * 根据三维评分生成针对性的改进建议。
     */
    private List<String> generateSuggestions(AgentStage stage, String content,
                                              int logicScore, int readabilityScore, int originalityScore) {
        List<String> suggestions = new ArrayList<>();

        if (logicScore < 60) {
            suggestions.add("逻辑性不足：建议增加标题层级结构（## / ###），使用「首先」「其次」「因此」等逻辑连接词增强连贯性");
        }
        if (readabilityScore < 60) {
            if (content.length() < 200) {
                suggestions.add("可读性不足：内容过短，建议扩充至 500 字以上并分段排版");
            } else {
                suggestions.add("可读性不足：建议增加 Markdown 格式化（粗体、代码块、引用块）和段落分隔");
            }
        }
        if (originalityScore < 60) {
            suggestions.add("原创性不足：词汇重复度较高，建议使用更多样的表达方式和同义替换");
        }

        // 阶段差异化建议
        switch (stage) {
            case DATA_ANALYSIS -> {
                ParsedMetrics metrics = metricsParser.parse(content);
                if (!metrics.hasData()) {
                    suggestions.add("数据分析缺失：内容未包含真实数值指标，请确保引用平台后台的真实数据");
                }
                if (!containsIgnoreCase(content, "环比") && !containsIgnoreCase(content, "同比")) {
                    suggestions.add("趋势维度缺失：建议补充环比/同比数据，避免依据单篇表现下结论");
                }
            }
            case CONTENT_CREATION -> {
                if (!containsAny(content, CONTENT_READABILITY_KEYWORDS)) {
                    suggestions.add("个人化不足：建议补充个人经历、实际案例或独家观点，增强不可替代性");
                }
            }
            case OPTIMIZATION -> {
                if (!containsAny(content, OPTIMIZATION_KEYWORDS)) {
                    suggestions.add("可操作性不足：建议明确策略调整方向、灰度验证方案和回滚机制");
                }
            }
            default -> {
                // 其它阶段使用通用建议
            }
        }

        // 如果所有维度都达标，给出肯定建议
        if (suggestions.isEmpty()) {
            suggestions.add("质量良好：各维度评分均达标，可继续优化细节以提升整体质量");
        }

        return suggestions;
    }

    // ──────────────── 工具方法 ────────────────

    /**
     * 统计正则模式在文本中的匹配次数。
     */
    private int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 统计子字符串在文本中出现的次数。
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }

    /**
     * 将分数限制在 [min, max] 范围内。
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 基于关键词命中数量计算加分（线性递增，有上限）。
     *
     * @param content  内容文本
     * @param keywords 关键词列表
     * @param perHit   每命中一个关键词的加分
     * @param max      最大加分上限
     * @return 加分值
     */
    private int keywordBonus(String content, String[] keywords, int perHit, int max) {
        int hitCount = 0;
        for (String kw : keywords) {
            if (content.contains(kw)) {
                hitCount++;
            }
        }
        return Math.min(hitCount * perHit, max);
    }

    /**
     * 检查内容是否包含任一关键词。
     */
    private boolean containsAny(String content, String[] keywords) {
        for (String kw : keywords) {
            if (content.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 大小写不敏感的包含检查。
     */
    private boolean containsIgnoreCase(String text, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        return text != null && text.toLowerCase(java.util.Locale.ROOT)
                .contains(keyword.toLowerCase(java.util.Locale.ROOT));
    }
}
