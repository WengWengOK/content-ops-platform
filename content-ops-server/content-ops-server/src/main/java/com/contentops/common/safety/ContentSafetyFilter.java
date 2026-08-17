package com.contentops.common.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容安全过滤器。
 *
 * <p>对文本内容进行多维度安全过滤，覆盖三大维度，并支持分级过滤与自定义词库：
 *
 * <h3>过滤维度</h3>
 * <ol>
 *   <li><b>敏感词检测</b>：按「政治、色情、暴力、广告」分类检测，支持自定义词库合并</li>
 *   <li><b>PII 检测与脱敏</b>：委托 {@link PiiDetector} 检测手机号、身份证、邮箱、银行卡、IP、微信号并脱敏</li>
 *   <li><b>有害内容检测</b>：按「自我伤害、仇恨言论、非法活动」分类检测</li>
 * </ol>
 *
 * <h3>过滤级别</h3>
 * <ul>
 *   <li>{@link FilterLevel#STRICT}：最严格，任意敏感词或有害内容命中即判定违规</li>
 *   <li>{@link FilterLevel#MODERATE}（默认）：适中，有害内容命中即违规，敏感词命中数达阈值才违规</li>
 *   <li>{@link FilterLevel#LENIENT}：宽松，仅严重有害内容命中才违规</li>
 * </ul>
 *
 * <p>内置默认词库为代表性示例，生产环境应通过 {@link SafetyProperties.ContentFilterConfig#getCustomSensitiveWords()}
 * 与 {@link SafetyProperties.ContentFilterConfig#getCustomHarmfulWords()} 注入完整词库。
 *
 * @see SafetyProperties.ContentFilterConfig
 * @see PiiDetector
 */
@Slf4j
@Component
public class ContentSafetyFilter {

    // ──────────────── 过滤级别 ────────────────

    /** 内容过滤级别。 */
    public enum FilterLevel {
        /** 最严格：任意敏感词或有害内容命中即违规。 */
        STRICT,
        /** 适中（默认）：有害内容命中即违规，敏感词命中数达阈值才违规。 */
        MODERATE,
        /** 宽松：仅严重有害内容命中才违规。 */
        LENIENT
    }

    /** 敏感词分类：政治。 */
    private static final String CATEGORY_POLITICAL = "政治";
    /** 敏感词分类：色情。 */
    private static final String CATEGORY_PORN = "色情";
    /** 敏感词分类：暴力。 */
    private static final String CATEGORY_VIOLENCE = "暴力";
    /** 敏感词分类：广告。 */
    private static final String CATEGORY_ADS = "广告";

    /** 有害内容分类：自我伤害。 */
    private static final String HARM_SELF_HARM = "自我伤害";
    /** 有害内容分类：仇恨言论。 */
    private static final String HARM_HATE_SPEECH = "仇恨言论";
    /** 有害内容分类：非法活动。 */
    private static final String HARM_ILLEGAL = "非法活动";

    // ──────────────── 风险评分权重 ────────────────
    private static final int SCORE_PER_SENSITIVE = 10;
    private static final int SCORE_SELF_HARM = 35;
    private static final int SCORE_HATE = 25;
    private static final int SCORE_ILLEGAL = 25;
    private static final int SCORE_PII = 15;

    // ──────────────── 内置默认敏感词库（代表性示例） ────────────────

    private static final Map<String, List<String>> DEFAULT_SENSITIVE_WORDS = Map.of(
            CATEGORY_POLITICAL, List.of("颠覆", "煽动颠覆", "分裂国家"),
            CATEGORY_PORN, List.of("色情", "裸聊", "成人电影", "黄色视频"),
            CATEGORY_VIOLENCE, List.of("砍人", "杀人方法", "虐待", "血腥暴力"),
            CATEGORY_ADS, List.of("加微信", "免费领取", "日赚万元", "点击链接赚钱", "代开发票")
    );

    // ──────────────── 内置默认有害内容词库 ────────────────

    private static final Map<String, List<String>> DEFAULT_HARMFUL_WORDS = Map.of(
            HARM_SELF_HARM, List.of("自杀", "想死", "结束生命", "割腕", "轻生", "不想活了",
                    "kill myself", "suicide", "self-harm", "end my life"),
            HARM_HATE_SPEECH, List.of("劣等种族", "应该被消灭", "去死吧", "种族歧视",
                    "hate them all", "inferior race"),
            HARM_ILLEGAL, List.of("毒品交易", "买卖枪支", "制造炸弹", "洗钱", "制毒方法",
                    "drug dealing", "how to make a bomb", "money laundering")
    );

    private final SafetyProperties properties;
    private final PiiDetector piiDetector;

    public ContentSafetyFilter(SafetyProperties properties, PiiDetector piiDetector) {
        this.properties = properties;
        this.piiDetector = piiDetector;
    }

    /**
     * 对文本内容进行综合安全过滤。
     *
     * @param content 待过滤文本
     * @return 过滤结果，包含是否通过、违规列表、净化后内容与风险评分
     */
    public ContentFilterResult filter(String content) {
        if (content == null || content.isBlank()) {
            return new ContentFilterResult(true, List.of(), content == null ? "" : content, 0);
        }

        SafetyProperties.ContentFilterConfig config = properties.getContentFilter();
        FilterLevel level = parseLevel(config.getLevel());
        List<String> violations = new ArrayList<>();
        int riskScore = 0;
        String sanitized = content;

        try {
            // 1. 敏感词检测
            if (config.isCheckSensitiveWords()) {
                WordDetectionResult swResult = detectWords(content,
                        mergeWords(DEFAULT_SENSITIVE_WORDS, config.getCustomSensitiveWords()), "敏感词");
                if (!swResult.matched().isEmpty()) {
                    violations.addAll(swResult.matched());
                    riskScore += Math.min(swResult.totalHits() * SCORE_PER_SENSITIVE, 40);
                    sanitized = maskWords(sanitized, swResult.allMatchedValues());
                    if (!passesSensitiveThreshold(level, swResult.totalHits(), config.getSensitiveWordThreshold())) {
                        violations.add(0, "敏感词命中数超阈值(" + swResult.totalHits() + ")");
                    }
                }
            }

            // 2. 有害内容检测
            if (config.isCheckHarmfulContent()) {
                WordDetectionResult hcResult = detectWords(content,
                        mergeWords(DEFAULT_HARMFUL_WORDS, config.getCustomHarmfulWords()), "有害内容");
                if (!hcResult.matched().isEmpty()) {
                    violations.addAll(hcResult.matched());
                    riskScore += scoreHarmful(hcResult);
                    sanitized = maskWords(sanitized, hcResult.allMatchedValues());
                }
            }

            // 3. PII 检测与脱敏
            if (config.isRedactPii()) {
                PiiDetector.PiiResult piiResult = piiDetector.detect(sanitized);
                if (piiResult.hasPii()) {
                    violations.add("检测到PII: " + piiResult.detectedTypes());
                    riskScore += SCORE_PII;
                    sanitized = piiResult.redactedContent();
                }
            }
        } catch (Exception e) {
            log.error("[ContentSafetyFilter] 内容过滤发生异常: {}", e.getMessage(), e);
        }

        riskScore = Math.min(riskScore, 100);
        boolean passed = determinePassed(level, violations, riskScore);

        if (!passed && properties.isLogViolations()) {
            log.warn("[ContentSafetyFilter] 内容未通过过滤 level={}, riskScore={}, violations={}",
                    level, riskScore, violations);
        }

        return new ContentFilterResult(passed, List.copyOf(violations), sanitized, riskScore);
    }

    // ──────────────── 敏感词/有害内容检测 ────────────────

    /**
     * 在文本中检测词库命中情况，按分类记录违规描述。
     *
     * @param content  文本
     * @param wordBank 按分类组织的词库
     * @param label    违规标签前缀（"敏感词" / "有害内容"）
     * @return 检测结果
     */
    private WordDetectionResult detectWords(String content, Map<String, List<String>> wordBank, String label) {
        List<String> matched = new ArrayList<>();
        List<String> allValues = new ArrayList<>();
        int totalHits = 0;
        String lowerContent = content.toLowerCase();

        for (Map.Entry<String, List<String>> entry : wordBank.entrySet()) {
            String category = entry.getKey();
            for (String word : entry.getValue()) {
                if (word == null || word.isBlank()) {
                    continue;
                }
                int hits = countOccurrences(lowerContent, word.toLowerCase());
                if (hits > 0) {
                    matched.add(label + "[" + category + "]: " + word + (hits > 1 ? "(x" + hits + ")" : ""));
                    allValues.add(word);
                    totalHits += hits;
                }
            }
        }
        return new WordDetectionResult(matched, allValues, totalHits);
    }

    /**
     * 统计子串出现次数（大小写不敏感）。
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
     * 将命中词在文本中替换为掩码（保留首尾各 1 字符）。
     */
    private String maskWords(String text, List<String> words) {
        String result = text;
        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            String mask = maskWord(word);
            // 大小写不敏感替换
            result = result.replaceAll("(?i)" + Pattern.quote(word), Matcher.quoteReplacement(mask));
        }
        return result;
    }

    /**
     * 单词打码：长度 ≤ 2 全部打码，否则保留首尾各 1 字符。
     */
    private String maskWord(String word) {
        if (word.length() <= 2) {
            return "*".repeat(word.length());
        }
        return word.charAt(0) + "*".repeat(word.length() - 2) + word.charAt(word.length() - 1);
    }

    /**
     * 合并内置默认词库与自定义词库（自定义词追加到对应分类）。
     */
    private Map<String, List<String>> mergeWords(Map<String, List<String>> defaults,
                                                 Map<String, List<String>> custom) {
        Map<String, List<String>> merged = new LinkedHashMap<>(defaults);
        if (custom != null) {
            for (Map.Entry<String, List<String>> entry : custom.entrySet()) {
                List<String> existing = new ArrayList<>(merged.getOrDefault(entry.getKey(), List.of()));
                existing.addAll(entry.getValue());
                merged.put(entry.getKey(), existing);
            }
        }
        return merged;
    }

    // ──────────────── 评级逻辑 ────────────────

    /**
     * 判断敏感词命中是否达到违规阈值（依过滤级别）。
     */
    private boolean passesSensitiveThreshold(FilterLevel level, int hits, int configuredThreshold) {
        int threshold = switch (level) {
            case STRICT -> 1;
            case MODERATE -> configuredThreshold;
            case LENIENT -> Math.max(configuredThreshold + 2, 3);
        };
        return hits < threshold;
    }

    /**
     * 根据有害内容分类命中计算风险评分增量。
     */
    private int scoreHarmful(WordDetectionResult result) {
        int score = 0;
        Set<String> values = Set.copyOf(result.allMatchedValues());
        // 依据分类关键词判断（简化：按命中描述中的分类标签计分）
        for (String desc : result.matched()) {
            if (desc.contains(HARM_SELF_HARM)) {
                score += SCORE_SELF_HARM;
            } else if (desc.contains(HARM_HATE_SPEECH)) {
                score += SCORE_HATE;
            } else if (desc.contains(HARM_ILLEGAL)) {
                score += SCORE_ILLEGAL;
            }
        }
        return Math.min(score, 60);
    }

    /**
     * 综合判定内容是否通过过滤。
     */
    private boolean determinePassed(FilterLevel level, List<String> violations, int riskScore) {
        if (violations.isEmpty()) {
            return true;
        }
        return switch (level) {
            case STRICT -> riskScore == 0;            // 严格模式：任何风险即不通过
            case MODERATE -> riskScore < 30;          // 适中模式：风险分 < 30 通过
            case LENIENT -> riskScore < 50;           // 宽松模式：风险分 < 50 通过
        };
    }

    /**
     * 解析过滤级别字符串，无效值回退到 MODERATE。
     */
    private FilterLevel parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return FilterLevel.MODERATE;
        }
        try {
            return FilterLevel.valueOf(level.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[ContentSafetyFilter] 无效的过滤级别 '{}', 回退到 MODERATE", level);
            return FilterLevel.MODERATE;
        }
    }

    // ──────────────── 结果与中间类型 ────────────────

    /**
     * 内容过滤结果。
     *
     * @param passed           是否通过过滤
     * @param violations       违规描述列表
     * @param sanitizedContent 净化后内容（敏感词打码、PII 脱敏）
     * @param riskScore        风险评分（0-100，越高越危险）
     */
    public record ContentFilterResult(
            boolean passed,
            List<String> violations,
            String sanitizedContent,
            int riskScore
    ) {
        public ContentFilterResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        /** 是否存在违规（即便最终通过）。 */
        public boolean hasViolations() {
            return !violations.isEmpty();
        }
    }

    /**
     * 词库检测中间结果。
     *
     * @param matched       违规描述列表（含分类与命中次数）
     * @param allMatchedValues 命中的原始词列表（用于打码）
     * @param totalHits     总命中次数
     */
    private record WordDetectionResult(
            List<String> matched,
            List<String> allMatchedValues,
            int totalHits
    ) {
    }
}
