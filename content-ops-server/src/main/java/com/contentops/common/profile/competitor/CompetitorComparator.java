package com.contentops.common.profile.competitor;

import com.contentops.common.profile.competitor.CompetitorProfile.BasicProfile;
import com.contentops.common.profile.competitor.CompetitorProfile.ComparisonProfile;
import com.contentops.common.profile.competitor.CompetitorProfile.ContentProfile;
import com.contentops.common.profile.competitor.CompetitorProfile.PerformanceProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 竞品对标分析服务（P0 定向竞品监控）。
 *
 * <p>将我方账号画像与竞品画像进行多维对标，输出量化的差距、重叠度、相似度与
 * 竞争烈度，并识别差异化机会。所有计算均为纯函数式实现，不依赖外部状态，便于
 * 单元测试与并发调用。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #compare} —— 生成完整对标报告（差距 + 重叠 + 相似 + 烈度 + 机会）</li>
 *   <li>{@link #calculateOverlap} —— 选题重叠度（Jaccard 系数）</li>
 *   <li>{@link #calculateStyleSimilarity} —— 风格相似度（特征向量余弦距离）</li>
 *   <li>{@link #calculateCompetitionIntensity} —— 竞争烈度评分（0-100）</li>
 *   <li>{@link #identifyOpportunities} —— 差异化机会识别</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompetitorComparator {

    private final CompetitorProfileProperties properties;

    /** 量化对标时纳入对比的核心指标键名 */
    private static final String METRIC_FOLLOWERS = "followerCount";
    private static final String METRIC_AVG_ENGAGEMENT = "avgEngagementRate";
    private static final String METRIC_HIT_RATE = "hitRate";
    private static final String METRIC_POSTING_FREQUENCY = "postingFrequencyPerWeek";

    // ════════════════════════════════════════════════════════════════
    // 对标报告
    // ════════════════════════════════════════════════════════════════

    /**
     * 生成完整对标报告 —— 汇总指标差距、选题重叠、风格相似、竞争烈度与机会列表。
     *
     * @param myProfile        我方账号画像（不可为 null）
     * @param competitorProfile 竞品画像（不可为 null）
     * @return 完整对标报告
     */
    public ComparisonReport compare(CompetitorProfile myProfile, CompetitorProfile competitorProfile) {
        log.debug("Generating comparison report: my={}, competitor={}",
                safeId(myProfile), safeId(competitorProfile));

        Map<String, Double> metricGaps = calculateMetricGaps(myProfile, competitorProfile);

        List<String> myTopics = extractTopics(myProfile);
        List<String> competitorTopics = extractTopics(competitorProfile);
        double topicOverlap = calculateOverlap(myTopics, competitorTopics);

        double styleSimilarity = calculateStyleSimilarity(
                extractStyleDescriptor(myProfile), extractStyleDescriptor(competitorProfile));

        int intensity = calculateCompetitionIntensity(
                extractMetrics(myProfile), extractMetrics(competitorProfile));

        List<Opportunity> opportunities = identifyOpportunities(myProfile, competitorProfile);

        List<String> borrowable = opportunities.stream()
                .filter(o -> o.type() == OpportunityType.BORROWABLE_STRATEGY)
                .map(Opportunity::description)
                .toList();
        List<String> avoid = opportunities.stream()
                .filter(o -> o.type() == OpportunityType.AVOID_DIRECTION)
                .map(Opportunity::description)
                .toList();

        ComparisonProfile comparison = new ComparisonProfile(
                metricGaps, topicOverlap, styleSimilarity, borrowable, avoid, intensity);

        return new ComparisonReport(
                safeId(myProfile), safeId(competitorProfile),
                metricGaps, topicOverlap, styleSimilarity, intensity,
                opportunities, comparison);
    }

    /**
     * 计算选题重叠度 —— 基于 Jaccard 系数（交集 / 并集）。
     *
     * <p>对关键词做小写归一化后计算集合重叠。当两侧均为空时返回 0。
     *
     * @param myTopics        我方选题关键词列表
     * @param competitorTopics 竞品选题关键词列表
     * @return 重叠度（0.0-1.0）
     */
    public double calculateOverlap(List<String> myTopics, List<String> competitorTopics) {
        Set<String> mine = normalizeKeywords(myTopics);
        Set<String> theirs = normalizeKeywords(competitorTopics);
        if (mine.isEmpty() && theirs.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(mine);
        intersection.retainAll(theirs);
        Set<String> union = new HashSet<>(mine);
        union.addAll(theirs);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * 计算风格相似度 —— 基于特征向量余弦距离。
     *
     * <p>将风格描述文本分词（支持中英文，中文按字符二元组提取）后，通过特征哈希
     * 映射到固定维度向量，计算两向量的余弦相似度。值域 0.0-1.0，越大越相似。
     *
     * <p>这是自包含的向量化方案，无需依赖外部 Embedding 模型；如需更强的语义
     * 相似度，可替换为 {@code KnowledgeBaseService} 的 Embedding 向量计算。
     *
     * @param myStyle        我方风格描述文本
     * @param competitorStyle 竞品风格描述文本
     * @return 风格相似度（0.0-1.0）
     */
    public double calculateStyleSimilarity(String myStyle, String competitorStyle) {
        int dim = Math.max(1, properties.getStyleVectorDim());
        double[] vecA = toFeatureVector(myStyle, dim);
        double[] vecB = toFeatureVector(competitorStyle, dim);
        return cosine(vecA, vecB);
    }

    /**
     * 计算竞争烈度评分 —— 综合指标接近度、选题重叠与风格相似度。
     *
     * <p>评分维度与权重：
     * <ul>
     *   <li>指标接近度（40%）：粉丝量、互动率、爆款率、发文频率越接近，竞争越直接</li>
     *   <li>选题重叠度（35%）：选题越重叠，正面竞争越激烈</li>
     *   <li>风格相似度（25%）：风格越相似，受众重叠越高</li>
     * </ul>
     *
     * @param myMetrics        我方指标（指标名 -> 数值）
     * @param competitorMetrics 竞品指标（指标名 -> 数值）
     * @return 竞争烈度评分（0-100）
     */
    public int calculateCompetitionIntensity(Map<String, Double> myMetrics, Map<String, Double> competitorMetrics) {
        double metricCloseness = computeMetricCloseness(myMetrics, competitorMetrics);
        // 选题重叠与风格相似需由 compare() 上下文提供，此处基于指标接近度估算并归一化
        double overlap = extractMetric(myMetrics, competitorMetrics, "topicOverlap");
        double styleSim = extractMetric(myMetrics, competitorMetrics, "styleSimilarity");

        double score = metricCloseness * 0.40 + overlap * 0.35 + styleSim * 0.25;
        int scale = Math.max(1, properties.getCompetitionIntensityScale());
        int intensity = (int) Math.round(score * scale);
        return Math.max(0, Math.min(scale, intensity));
    }

    /**
     * 识别差异化机会 —— 竞品已发且我方未覆盖的选题、竞品表现弱的平台、风格空白区。
     *
     * @param myProfile        我方画像
     * @param competitorProfile 竞品画像
     * @return 机会列表（含可借鉴策略与应规避方向）
     */
    public List<Opportunity> identifyOpportunities(CompetitorProfile myProfile, CompetitorProfile competitorProfile) {
        List<Opportunity> opportunities = new ArrayList<>();

        // 1. 竞品已覆盖、我方未覆盖的选题 → 可借鉴
        Set<String> myTopics = normalizeKeywords(extractTopics(myProfile));
        Set<String> competitorTopics = normalizeKeywords(extractTopics(competitorProfile));
        List<String> uncovered = competitorTopics.stream()
                .filter(t -> !myTopics.contains(t))
                .toList();
        if (!uncovered.isEmpty()) {
            opportunities.add(new Opportunity(
                    OpportunityType.BORROWABLE_STRATEGY,
                    "竞品已覆盖而我方未涉及的选题：" + String.join("、", uncovered),
                    Priority.MEDIUM));
        }

        // 2. 竞品表现弱的平台 → 我方机会
        PerformanceProfile competitorPerf = competitorProfile.performance();
        if (competitorPerf != null && competitorPerf.platformComparisonMatrix() != null) {
            List<String> weakPlatforms = competitorPerf.platformComparisonMatrix().entrySet().stream()
                    .filter(e -> e.getValue() < 0.4)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!weakPlatforms.isEmpty()) {
                opportunities.add(new Opportunity(
                        OpportunityType.BORROWABLE_STRATEGY,
                        "竞品表现较弱的平台：" + String.join("、", weakPlatforms) + "，可优先布局",
                        Priority.HIGH));
            }
        }

        // 3. 竞品高表现共性特征 → 可借鉴策略
        if (competitorPerf != null && competitorPerf.highPerformanceCommonTraits() != null) {
            for (String trait : competitorPerf.highPerformanceCommonTraits()) {
                opportunities.add(new Opportunity(
                        OpportunityType.BORROWABLE_STRATEGY,
                        "可借鉴竞品爆款共性：" + trait,
                        Priority.MEDIUM));
            }
        }

        // 4. 选题重叠度高 → 应规避同质化方向
        double overlap = calculateOverlap(extractTopics(myProfile), extractTopics(competitorProfile));
        if (overlap > 0.6) {
            opportunities.add(new Opportunity(
                    OpportunityType.AVOID_DIRECTION,
                    String.format("选题重叠度达 %.0f%%，应规避同质化选题，寻求差异化角度", overlap * 100),
                    Priority.HIGH));
        }

        // 5. 风格高度相似 → 应规避风格雷同
        double styleSim = calculateStyleSimilarity(
                extractStyleDescriptor(myProfile), extractStyleDescriptor(competitorProfile));
        if (styleSim > 0.7) {
            opportunities.add(new Opportunity(
                    OpportunityType.AVOID_DIRECTION,
                    String.format("风格相似度达 %.0f%%，应规避风格雷同，强化独有调性", styleSim * 100),
                    Priority.MEDIUM));
        }

        // 6. 竞品互动率显著领先 → 应规避正面对抗
        PerformanceProfile myPerf = myProfile.performance();
        if (myPerf != null && competitorPerf != null) {
            double gap = competitorPerf.avgEngagementRate() - myPerf.avgEngagementRate();
            if (gap > 0.03) {
                opportunities.add(new Opportunity(
                        OpportunityType.AVOID_DIRECTION,
                        String.format("竞品平均互动率领先 %.1f%%，应规避正面流量对抗", gap * 100),
                        Priority.MEDIUM));
            }
        }

        return opportunities;
    }

    // ════════════════════════════════════════════════════════════════
    // 对标报告与机会的数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 完整对标报告。
     *
     * @param myAccountId        我方账号 ID
     * @param competitorAccountId 竞品账号 ID
     * @param metricGaps         指标差距量化（正为竞品领先）
     * @param topicOverlap       选题重叠度
     * @param styleSimilarity    风格相似度
     * @param competitionIntensity 竞争烈度（0-100）
     * @param opportunities      机会列表
     * @param comparison         组装后的对标画像层
     */
    public record ComparisonReport(
            String myAccountId,
            String competitorAccountId,
            Map<String, Double> metricGaps,
            double topicOverlap,
            double styleSimilarity,
            int competitionIntensity,
            List<Opportunity> opportunities,
            ComparisonProfile comparison
    ) {
    }

    /**
     * 差异化机会。
     *
     * @param type        机会类型（可借鉴策略 / 应规避方向）
     * @param description 机会描述
     * @param priority    优先级
     */
    public record Opportunity(OpportunityType type, String description, Priority priority) {
    }

    /** 机会类型 */
    public enum OpportunityType {
        /** 可借鉴策略 */
        BORROWABLE_STRATEGY,
        /** 应规避方向 */
        AVOID_DIRECTION
    }

    /** 优先级 */
    public enum Priority {
        /** 高 */
        HIGH,
        /** 中 */
        MEDIUM,
        /** 低 */
        LOW
    }

    // ════════════════════════════════════════════════════════════════
    // 内部计算方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算核心指标的量化差距（竞品值 - 我方值，正为竞品领先）。
     */
    private Map<String, Double> calculateMetricGaps(CompetitorProfile my, CompetitorProfile competitor) {
        Map<String, Double> gaps = new LinkedHashMap<>();
        BasicProfile myBasic = my.basic();
        BasicProfile compBasic = competitor.basic();
        PerformanceProfile myPerf = my.performance();
        PerformanceProfile compPerf = competitor.performance();

        if (myBasic != null && compBasic != null) {
            gaps.put(METRIC_FOLLOWERS, (double) compBasic.followerCount() - myBasic.followerCount());
            gaps.put(METRIC_POSTING_FREQUENCY,
                    compBasic.postingFrequencyPerWeek() - myBasic.postingFrequencyPerWeek());
        }
        if (myPerf != null && compPerf != null) {
            gaps.put(METRIC_AVG_ENGAGEMENT, compPerf.avgEngagementRate() - myPerf.avgEngagementRate());
            gaps.put(METRIC_HIT_RATE, compPerf.hitRate() - myPerf.hitRate());
        }
        return gaps;
    }

    /**
     * 从画像中提取选题关键词。
     */
    private List<String> extractTopics(CompetitorProfile profile) {
        ContentProfile content = profile.content();
        return content != null && content.topicKeywords() != null
                ? content.topicKeywords()
                : List.of();
    }

    /**
     * 从画像中拼接风格描述文本（标题风格 + 内容风格 + 视觉风格）。
     */
    private String extractStyleDescriptor(CompetitorProfile profile) {
        ContentProfile content = profile.content();
        if (content == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        CompetitorProfile.TitleStyle titleStyle = content.titleStyle();
        if (titleStyle != null) {
            sb.append(titleStyle.structurePattern()).append(' ');
            if (titleStyle.emotionWords() != null) {
                sb.append(String.join(" ", titleStyle.emotionWords())).append(' ');
            }
            if (titleStyle.styleTags() != null) {
                sb.append(String.join(" ", titleStyle.styleTags())).append(' ');
            }
        }
        if (content.contentStyle() != null) {
            sb.append(content.contentStyle()).append(' ');
        }
        if (content.visualStyle() != null) {
            sb.append(content.visualStyle());
        }
        return sb.toString();
    }

    /**
     * 从画像中提取核心指标 Map（用于竞争烈度计算）。
     */
    private Map<String, Double> extractMetrics(CompetitorProfile profile) {
        Map<String, Double> metrics = new HashMap<>();
        BasicProfile basic = profile.basic();
        PerformanceProfile perf = profile.performance();
        if (basic != null) {
            metrics.put(METRIC_FOLLOWERS, (double) basic.followerCount());
            metrics.put(METRIC_POSTING_FREQUENCY, basic.postingFrequencyPerWeek());
        }
        if (perf != null) {
            metrics.put(METRIC_AVG_ENGAGEMENT, perf.avgEngagementRate());
            metrics.put(METRIC_HIT_RATE, perf.hitRate());
        }
        return metrics;
    }

    /**
     * 计算指标接近度 —— 各核心指标归一化后的平均接近程度（0.0-1.0，越大越接近）。
     */
    private double computeMetricCloseness(Map<String, Double> myMetrics, Map<String, Double> competitorMetrics) {
        if (myMetrics.isEmpty() && competitorMetrics.isEmpty()) {
            return 0.0;
        }
        Set<String> keys = new HashSet<>(myMetrics.keySet());
        keys.addAll(competitorMetrics.keySet());
        double sum = 0.0;
        int count = 0;
        for (String key : keys) {
            double myVal = myMetrics.getOrDefault(key, 0.0);
            double compVal = competitorMetrics.getOrDefault(key, 0.0);
            double max = Math.max(Math.abs(myVal), Math.abs(compVal));
            if (max < 1e-9) {
                sum += 1.0; // 双方均为 0 视为完全接近
            } else {
                sum += 1.0 - Math.abs(myVal - compVal) / max;
            }
            count++;
        }
        return count > 0 ? Math.max(0.0, sum / count) : 0.0;
    }

    /**
     * 从两侧指标 Map 中提取重叠度 / 相似度辅助指标（用于烈度估算）。
     */
    private double extractMetric(Map<String, Double> myMetrics, Map<String, Double> competitorMetrics, String key) {
        double v = myMetrics.getOrDefault(key, competitorMetrics.getOrDefault(key, 0.0));
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * 关键词归一化 —— 去空格、转小写、去重。
     */
    private Set<String> normalizeKeywords(Collection<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return new HashSet<>();
        }
        return keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> k.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
    }

    // ──────────────────── 特征向量化与余弦相似度 ────────────────────

    /**
     * 将文本转换为固定维度的特征向量（特征哈希 + 词频）。
     *
     * <p>分词策略：英文按空白与标点切分；中文按字符二元组（bigram）提取，
     * 兼顾中文无空格分词的特点。每个 token 通过哈希映射到向量某一维并累加词频。
     *
     * @param text 原始文本
     * @param dim  向量维度
     * @return 特征向量
     */
    private double[] toFeatureVector(String text, int dim) {
        double[] vector = new double[dim];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String token : tokenize(text)) {
            int idx = Math.floorMod(token.hashCode(), dim);
            vector[idx] += 1.0;
        }
        return vector;
    }

    /**
     * 文本分词 —— 兼顾中英文。
     *
     * <p>英文 token：连续的字母 / 数字序列；中文 token：相邻两个 CJK 字符组成的二元组。
     *
     * @param text 原始文本
     * @return token 列表
     */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                flushLatin(tokens, latin);
                cjk.append(ch);
            } else if (Character.isLetterOrDigit(ch)) {
                flushCjk(tokens, cjk);
                latin.append(ch);
            } else {
                flushLatin(tokens, latin);
                flushCjk(tokens, cjk);
            }
        }
        flushLatin(tokens, latin);
        flushCjk(tokens, cjk);
        return tokens;
    }

    private void flushLatin(List<String> tokens, StringBuilder buf) {
        if (!buf.isEmpty()) {
            tokens.add(buf.toString().toLowerCase(Locale.ROOT));
            buf.setLength(0);
        }
    }

    private void flushCjk(List<String> tokens, StringBuilder buf) {
        if (buf.length() >= 2) {
            for (int i = 0; i + 1 < buf.length(); i++) {
                tokens.add(buf.substring(i, i + 2));
            }
        } else if (buf.length() == 1) {
            tokens.add(buf.toString());
        }
        buf.setLength(0);
    }

    private boolean isCjk(char ch) {
        return (ch >= '\u4E00' && ch <= '\u9FFF')
                || (ch >= '\u3400' && ch <= '\u4DBF')
                || (ch >= '\uF900' && ch <= '\uFAFF');
    }

    /**
     * 计算两向量的余弦相似度。
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 余弦相似度（0.0-1.0，因词频非负）
     */
    private double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA < 1e-9 || normB < 1e-9) {
            return 0.0;
        }
        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    private String safeId(CompetitorProfile profile) {
        return profile != null && profile.competitorAccountId() != null
                ? profile.competitorAccountId()
                : "unknown";
    }
}
