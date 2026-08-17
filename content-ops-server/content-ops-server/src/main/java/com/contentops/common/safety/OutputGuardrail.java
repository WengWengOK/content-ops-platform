package com.contentops.common.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出内容护栏。
 *
 * <p>在 LLM 生成结果返回前，对输出内容进行四项检查，每项均可独立开关并配置阈值：
 *
 * <h3>检查项</h3>
 * <ol>
 *   <li><b>敏感信息泄露</b>：检测输出是否包含 PII、API 密钥、系统提示词等不应泄露的信息</li>
 *   <li><b>有害建议</b>：检测输出是否包含武器、毒品、非法活动的指导性内容</li>
 *   <li><b>版权风险</b>：基于 n-gram shingling 计算与已知受版权保护文本的 Jaccard 相似度</li>
 *   <li><b>幻觉检测</b>：检测无来源引用的事实声明（统计数据、断言），数量超阈值即告警</li>
 *   <li><b>格式验证</b>：校验输出是否符合预期格式（json / markdown / plain）</li>
 * </ol>
 *
 * <p>当任一启用的检查项未通过时，整体判定为未通过（passed=false）。
 *
 * @see SafetyProperties.OutputGuardConfig
 * @see PiiDetector
 */
@Slf4j
@Component
public class OutputGuardrail {

    // ──────────────── 预编译正则：敏感信息泄露 ────────────────

    /** OpenAI 风格 API Key：sk- 开头。 */
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("(?i)\\bsk-[A-Za-z0-9]{20,}\\b");

    /** AWS Access Key：AKIA 开头。 */
    private static final Pattern AWS_KEY_PATTERN =
            Pattern.compile("\\bAKIA[A-Z0-9]{16}\\b");

    /** 通用密钥赋值：api_key/secret/password/token=... */
    private static final Pattern SECRET_ASSIGN_PATTERN =
            Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|token|access[_-]?key)\\s*[:=]\\s*['\"]?[A-Za-z0-9/+=_-]{8,}");

    /** Bearer Token。 */
    private static final Pattern BEARER_TOKEN_PATTERN =
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9_\\-.]{20,}");

    /** 系统提示词泄露标志。 */
    private static final Pattern SYSTEM_PROMPT_LEAK_PATTERN =
            Pattern.compile("(?i)(my\\s+(instructions?|system\\s+prompt|rules?)\\s+(are|is)|你是?一个?(AI|人工智能|大语言模型|语言模型)|as\\s+an\\s+ai\\s+(language\\s+)?model|i\\s+am\\s+programmed\\s+to)");

    // ──────────────── 预编译正则：有害建议 ────────────────

    /** 有害指导措辞（中英文）。 */
    private static final Pattern HARMFUL_ADVICE_PATTERN =
            Pattern.compile("(?i)(how\\s+to\\s+(make|build|create|synthesize)\\s+(a\\s+)?(bomb|weapon|explosive|drug|poison|meth))" +
                    "|(制作|合成|提炼|配置).{0,8}(炸弹|爆炸物|武器|毒品|毒药|冰毒)" +
                    "|(步骤|方法|教程).{0,8}(制毒|制弹|制造炸弹|非法)");

    // ──────────────── 预编译正则：幻觉检测 ────────────────

    /** 事实声明标志（统计数据、研究引用、断言）。 */
    private static final Pattern FACT_CLAIM_PATTERN =
            Pattern.compile("(\\d+(\\.\\d+)?%)"                              // 百分比
                    + "|(超过\\d+|高达\\d+|约\\d+|大约\\d+|将近\\d+)"          // 中文数量断言
                    + "|(\\d{4}年)"                                          // 年份
                    + "|(据(统计|研究|调查|报道|数据))"                         // 中文研究引用
                    + "|(?i)(according\\s+to|studies\\s+show|research\\s+indicates|data\\s+shows|statistics\\s+show)");

    /** 来源引用标志（有来源则视为有依据）。 */
    private static final Pattern SOURCE_ATTRIBUTION_PATTERN =
            Pattern.compile("(来源[:：]|参考文献|据.{1,15}报道|引用)" +
                    "|(?i)(source\\s*[:：]|reference|cited\\s+from|according\\s+to\\s+\\w+)");

    // ──────────────── 版权相似度：shingle 大小 ────────────────
    private static final int SHINGLE_SIZE = 5;

    private final SafetyProperties properties;
    private final PiiDetector piiDetector;

    public OutputGuardrail(SafetyProperties properties, PiiDetector piiDetector) {
        this.properties = properties;
        this.piiDetector = piiDetector;
    }

    /**
     * 对 LLM 输出进行护栏检查。
     *
     * @param output LLM 生成的输出文本
     * @return 护栏检查结果，包含是否通过、违规列表、净化后内容与各检查项状态
     */
    public OutputGuardResult check(String output) {
        if (output == null || output.isBlank()) {
            return new OutputGuardResult(true, List.of(), output == null ? "" : output,
                    Map.of("empty", true));
        }

        SafetyProperties.OutputGuardConfig config = properties.getOutputGuard();
        List<String> violations = new ArrayList<>();
        Map<String, Boolean> checks = new LinkedHashMap<>();
        String sanitized = output;

        try {
            // 1. 敏感信息泄露
            if (config.isCheckSensitiveLeak()) {
                boolean ok = checkSensitiveLeak(output, violations);
                checks.put("sensitiveLeak", ok);
                if (!ok) {
                    sanitized = sanitizeSecrets(sanitized);
                }
            }

            // 2. 有害建议
            if (config.isCheckHarmfulAdvice()) {
                boolean ok = checkHarmfulAdvice(output, violations);
                checks.put("harmfulAdvice", ok);
            }

            // 3. 版权风险
            if (config.isCheckCopyright()) {
                boolean ok = checkCopyright(output, config, violations);
                checks.put("copyright", ok);
            }

            // 4. 幻觉检测
            if (config.isCheckHallucination()) {
                boolean ok = checkHallucination(output, config, violations);
                checks.put("hallucination", ok);
            }

            // 5. 格式验证
            if (config.isCheckFormat()) {
                boolean ok = checkFormat(output, config.getExpectedFormat(), violations);
                checks.put("format", ok);
            }
        } catch (Exception e) {
            log.error("[OutputGuardrail] 输出护栏检查发生异常: {}", e.getMessage(), e);
        }

        boolean passed = violations.isEmpty();
        if (!passed && properties.isLogViolations()) {
            log.warn("[OutputGuardrail] 输出未通过护栏检查 violations={}", violations);
        }
        return new OutputGuardResult(passed, List.copyOf(violations), sanitized, Map.copyOf(checks));
    }

    // ──────────────── 敏感信息泄露检查 ────────────────

    /**
     * 检查输出是否泄露敏感信息（PII / 密钥 / 系统提示词）。
     *
     * @return true 表示通过（未泄露）
     */
    private boolean checkSensitiveLeak(String output, List<String> violations) {
        boolean leaked = false;

        // PII 泄露
        if (piiDetector.containsPii(output)) {
            violations.add("敏感信息泄露: 输出包含PII");
            leaked = true;
        }
        // API 密钥泄露
        if (API_KEY_PATTERN.matcher(output).find()) {
            violations.add("敏感信息泄露: 检测到API Key");
            leaked = true;
        }
        if (AWS_KEY_PATTERN.matcher(output).find()) {
            violations.add("敏感信息泄露: 检测到AWS Access Key");
            leaked = true;
        }
        if (SECRET_ASSIGN_PATTERN.matcher(output).find()) {
            violations.add("敏感信息泄露: 检测到密钥赋值");
            leaked = true;
        }
        if (BEARER_TOKEN_PATTERN.matcher(output).find()) {
            violations.add("敏感信息泄露: 检测到Bearer Token");
            leaked = true;
        }
        // 系统提示词泄露
        if (SYSTEM_PROMPT_LEAK_PATTERN.matcher(output).find()) {
            violations.add("敏感信息泄露: 系统提示词泄露");
            leaked = true;
        }
        return !leaked;
    }

    /**
     * 净化密钥类敏感信息（替换为 [REDACTED]）。
     */
    private String sanitizeSecrets(String text) {
        String result = text;
        result = API_KEY_PATTERN.matcher(result).replaceAll("[REDACTED]");
        result = AWS_KEY_PATTERN.matcher(result).replaceAll("[REDACTED]");
        result = BEARER_TOKEN_PATTERN.matcher(result).replaceAll("Bearer [REDACTED]");
        result = SECRET_ASSIGN_PATTERN.matcher(result).replaceAll("$1=[REDACTED]");
        return result;
    }

    // ──────────────── 有害建议检查 ────────────────

    /**
     * 检查输出是否包含有害指导性内容。
     *
     * @return true 表示通过（无有害建议）
     */
    private boolean checkHarmfulAdvice(String output, List<String> violations) {
        Matcher matcher = HARMFUL_ADVICE_PATTERN.matcher(output);
        if (matcher.find()) {
            violations.add("有害建议: 检测到武器/毒品/非法活动指导");
            return false;
        }
        return true;
    }

    // ──────────────── 版权风险检查 ────────────────

    /**
     * 检查输出与已知受版权保护文本的相似度。
     * <p>使用 n-gram shingling + Jaccard 相似度，超过阈值判定为版权风险。
     *
     * @return true 表示通过（无版权风险）
     */
    private boolean checkCopyright(String output, SafetyProperties.OutputGuardConfig config,
                                   List<String> violations) {
        List<String> copyrightedTexts = config.getCopyrightedTexts();
        if (copyrightedTexts == null || copyrightedTexts.isEmpty()) {
            return true;
        }

        Set<String> outputShingles = shingles(output, SHINGLE_SIZE);
        if (outputShingles.isEmpty()) {
            return true;
        }

        double maxSimilarity = 0.0;
        String matchedExcerpt = null;
        for (String copyrighted : copyrightedTexts) {
            if (copyrighted == null || copyrighted.isBlank()) {
                continue;
            }
            double similarity = jaccardSimilarity(outputShingles, shingles(copyrighted, SHINGLE_SIZE));
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                matchedExcerpt = copyrighted;
            }
        }

        if (maxSimilarity >= config.getCopyrightSimilarityThreshold()) {
            violations.add(String.format("版权风险: 与已知文本相似度 %.0f%%", maxSimilarity * 100));
            log.debug("[OutputGuardrail] 版权命中, 相似度={}, 摘要={}",
                    String.format("%.3f", maxSimilarity),
                    matchedExcerpt == null ? "" : matchedExcerpt.substring(0, Math.min(30, matchedExcerpt.length())));
            return false;
        }
        return true;
    }

    /**
     * 生成文本的 n-gram shingle 集合（按词/字分词，混合中英文）。
     */
    private Set<String> shingles(String text, int n) {
        List<String> tokens = tokenize(text);
        if (tokens.size() < n) {
            return Set.of(String.join("", tokens));
        }
        Set<String> shingles = new HashSet<>();
        for (int i = 0; i <= tokens.size() - n; i++) {
            shingles.add(String.join("", tokens.subList(i, i + n)));
        }
        return shingles;
    }

    /**
     * 混合分词：连续中文按单字切分，连续英文按词切分。
     */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isChinese(c)) {
                if (current.length() > 0) {
                    addEnglishTokens(current.toString(), tokens);
                    current.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c) || c == '\'' || c == '-') {
                current.append(c);
            } else {
                if (current.length() > 0) {
                    addEnglishTokens(current.toString(), tokens);
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            addEnglishTokens(current.toString(), tokens);
        }
        return tokens;
    }

    /** 将英文串按空格/标点拆分为词后加入 tokens。 */
    private void addEnglishTokens(String s, List<String> tokens) {
        for (String word : s.split("[\\s_]+")) {
            if (!word.isEmpty()) {
                tokens.add(word.toLowerCase());
            }
        }
    }

    private boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    /**
     * 计算 Jaccard 相似度：|A ∩ B| / |A ∪ B|。
     */
    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    // ──────────────── 幻觉检测 ────────────────

    /**
     * 启发式幻觉检测：统计无来源引用的事实声明数量，超阈值即告警。
     *
     * @return true 表示通过（事实声明数量在阈值内）
     */
    private boolean checkHallucination(String output, SafetyProperties.OutputGuardConfig config,
                                       List<String> violations) {
        Matcher claimMatcher = FACT_CLAIM_PATTERN.matcher(output);
        int claimCount = 0;
        while (claimMatcher.find()) {
            claimCount++;
        }
        if (claimCount == 0) {
            return true;
        }

        // 检查是否存在来源引用
        boolean hasSource = SOURCE_ATTRIBUTION_PATTERN.matcher(output).find();
        // 有来源引用时，对事实声明的容忍度提高（视为有依据）
        int effectiveThreshold = hasSource
                ? config.getHallucinationClaimThreshold() + 5
                : config.getHallucinationClaimThreshold();

        if (claimCount > effectiveThreshold) {
            violations.add(String.format("幻觉风险: 检测到%d处事实声明%s, 超过阈值%d",
                    claimCount, hasSource ? "(部分有来源)" : "(无来源引用)", effectiveThreshold));
            return false;
        }
        return true;
    }

    // ──────────────── 格式验证 ────────────────

    /**
     * 校验输出是否符合预期格式。
     *
     * @param expectedFormat json / markdown / plain / none
     * @return true 表示格式符合
     */
    private boolean checkFormat(String output, String expectedFormat, List<String> violations) {
        if (expectedFormat == null || expectedFormat.isBlank()) {
            return true;
        }
        String trimmed = output.trim();
        return switch (expectedFormat.toLowerCase()) {
            case "none", "" -> true;
            case "json" -> {
                if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                        || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                    yield true;
                }
                violations.add("格式验证: 预期JSON格式但输出不匹配");
                yield false;
            }
            case "markdown" -> {
                if (trimmed.contains("#") || trimmed.contains("**")
                        || trimmed.contains("```") || trimmed.matches("(?m)^[-*+]\\s.+")) {
                    yield true;
                }
                violations.add("格式验证: 预期Markdown格式但输出缺少格式化标记");
                yield false;
            }
            case "plain" -> true;
            default -> {
                log.warn("[OutputGuardrail] 未知的预期格式 '{}', 跳过格式校验", expectedFormat);
                yield true;
            }
        };
    }

    // ──────────────── 结果类型 ────────────────

    /**
     * 输出护栏检查结果。
     *
     * @param passed           是否通过全部启用的检查项
     * @param violations       违规描述列表
     * @param sanitizedContent 净化后内容（密钥类泄露已脱敏）
     * @param checks           各检查项通过状态（key=检查项名, value=是否通过）
     */
    public record OutputGuardResult(
            boolean passed,
            List<String> violations,
            String sanitizedContent,
            Map<String, Boolean> checks
    ) {
        public OutputGuardResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
            checks = checks == null ? Map.of() : Map.copyOf(checks);
        }

        /** 是否存在违规（即便最终通过）。 */
        public boolean hasViolations() {
            return !violations.isEmpty();
        }
    }
}
