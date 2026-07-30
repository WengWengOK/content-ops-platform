package com.contentops.common.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 注入检测器。
 *
 * <p>在调用 LLM 前对用户输入进行 Prompt 注入攻击检测，覆盖三类攻击模式，
 * 并支持中英文双语检测：
 *
 * <h3>检测模式</h3>
 * <ol>
 *   <li><b>直接注入 (DIRECT)</b>：检测「ignore previous instructions」「system:」「你是GPT」
 *       「越狱」等试图覆盖、操纵系统提示或角色设定的攻击模式。</li>
 *   <li><b>间接注入 (INDIRECT)</b>：检测 Markdown 隐藏链接（javascript:/data: URI）、
 *       图片注入、HTML 脚本标签、事件处理器、HTML 注释中的隐藏指令、零宽字符等。</li>
 *   <li><b>编码绕过 (ENCODED)</b>：检测 Base64 编码、Unicode 转义序列、
 *       URL 编码（%XX）、HTML 实体中隐藏的可疑内容，解码后二次检测注入模式。</li>
 * </ol>
 *
 * <h3>置信度模型</h3>
 * <p>不同模式按严重度分级累加置信度，上限 1.0：
 * <ul>
 *   <li>高严重度（显式覆盖指令，如 ignore/forget/system:）：单次命中 +0.6</li>
 *   <li>中严重度（角色操纵，如 you are/act as/pretend）：单次命中 +0.4</li>
 *   <li>低严重度（可疑措辞，如 developer mode/repeat after me）：单次命中 +0.2</li>
 *   <li>间接注入：单次命中 +0.25</li>
 *   <li>编码绕过（解码后命中注入模式）：单次命中 +0.3</li>
 * </ul>
 * <p>当综合置信度 ≥ {@link SafetyProperties.InjectionConfig#getConfidenceThreshold()} 时判定为恶意注入。
 *
 * <p>所有正则表达式在类加载时预编译，避免重复编译开销。
 *
 * @see SafetyProperties.InjectionConfig
 */
@Slf4j
@Component
public class PromptInjectionDetector {

    // ──────────────── 攻击类型 ────────────────

    /** 攻击类型枚举。 */
    public enum AttackType {
        /** 直接注入 */
        DIRECT,
        /** 间接注入 */
        INDIRECT,
        /** 编码绕过 */
        ENCODED,
        /** 无攻击 */
        NONE
    }

    /** 高严重度（显式覆盖指令）。 */
    private static final double SEVERITY_HIGH = 0.6;
    /** 中严重度（角色操纵）。 */
    private static final double SEVERITY_MEDIUM = 0.4;
    /** 低严重度（可疑措辞）。 */
    private static final double SEVERITY_LOW = 0.2;
    /** 间接注入严重度。 */
    private static final double SEVERITY_INDIRECT = 0.25;
    /** 编码绕过严重度。 */
    private static final double SEVERITY_ENCODED = 0.3;

    // ──────────────── 直接注入：高严重度模式（中英文） ────────────────

    /** 高严重度直接注入模式列表（regex, 描述）。 */
    private static final List<PatternEntry> DIRECT_HIGH_PATTERNS = List.of(
            entry("(?i)ignore\\s+(all\\s+|the\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?|directions?|messages?)",
                    "忽略先前指令"),
            entry("(?i)disregard\\s+(the\\s+)?(above|previous|prior|all)\\s+(instructions?|prompts?|rules?|directions?)",
                    "无视上述指令"),
            entry("(?i)forget\\s+(all\\s+|your\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|context|rules?)",
                    "忘记先前指令"),
            entry("(?i)\\bsystem\\s*(prompt|message|instruction|rule)s?\\s*[:：]",
                    "伪造系统提示"),
            entry("(?i)\\bjailbreak\\b", "越狱攻击"),
            entry("(?i)\\bDAN\\s+mode\\b|do\\s+anything\\s+now", "DAN模式攻击"),
            entry("(?i)(reveal|show|display|print|output)\\s.{0,20}(system\\s+prompt|initial\\s+(instructions?|message)|hidden\\s+rules?)",
                    "诱导泄露系统提示"),
            entry("(?i)\\boverride\\s+(your\\s+|the\\s+|all\\s+)?(instructions?|rules?|guidelines?|directives?)",
                    "覆盖指令"),
            entry("忽略(上面|之前|先前|以上|上面所有).{0,10}(指令|提示|规则|内容|要求|设定)",
                    "中文-忽略先前指令"),
            entry("无视(上面|之前|先前|以上).{0,10}(指令|提示|规则|要求|设定)",
                    "中文-无视指令"),
            entry("忘记(之前|先前|上面|以上).{0,10}(指令|提示|内容|对话|设定|规则)",
                    "中文-忘记指令"),
            entry("(系统|初始)(提示词?|指令|消息|规则)[:：]", "中文-伪造系统提示"),
            entry("(显示|告诉我|输出|打印|展示).{0,10}(系统提示|初始指令|系统规则|隐藏规则|预设)",
                    "中文-诱导泄露系统提示"),
            entry("(覆盖|重写|替换).{0,10}(指令|规则|设定|系统提示)", "中文-覆盖指令"),
            entry("越狱|解除(所有)?限制|取消(所有)?限制", "中文-越狱攻击")
    );

    // ──────────────── 直接注入：中严重度模式（角色操纵） ────────────────

    /** 中严重度直接注入模式列表。 */
    private static final List<PatternEntry> DIRECT_MEDIUM_PATTERNS = List.of(
            entry("(?i)\\byou\\s+are\\s+(now\\s+)?(GPT|ChatGPT|an?\\s+AI|DAN|a\\s+developer|an?\\s+assistant\\s+with\\s+no\\s+restrictions)",
                    "身份重定义"),
            entry("(?i)\\bact\\s+as\\s+(if\\s+)?you\\s+(are|have\\s+no)", "角色扮演操纵"),
            entry("(?i)\\bpretend\\s+(you\\s+are|to\\s+be)\\b", "伪装身份"),
            entry("(?i)\\bfrom\\s+now\\s+on\\b.{0,40}(you\\s+are|act\\s+as|ignore|no\\s+restrictions?|no\\s+rules?)",
                    "重新设定规则"),
            entry("(?i)\\bdo\\s+not\\s+follow\\s+(your|the|any|all)\\s+(instructions?|rules?|guidelines?)",
                    "拒绝遵循指令"),
            entry("你(现在|从现在开始|接下来)?(是|扮演|假装是?|模拟).{0,15}(AI|人工智能|大语言模型|GPT|ChatGPT|没有任何限制|不受限制|开发者)",
                    "中文-身份重定义"),
            entry("(扮演|假装|模拟).{0,10}(一个|角色|没有限制|不受限制)", "中文-角色扮演操纵"),
            entry("从现在开始.{0,30}(你是|忽略|无需|不要遵守|没有限制|不受限制)", "中文-重新设定规则"),
            entry("不要(遵守|遵循|理会|遵循).{0,10}(指令|规则|要求|限制)", "中文-拒绝遵循指令"),
            entry("你(没有|不具备|不受).{0,5}(任何)?限制", "中文-声称无限制")
    );

    // ──────────────── 直接注入：低严重度模式 ────────────────

    /** 低严重度直接注入模式列表。 */
    private static final List<PatternEntry> DIRECT_LOW_PATTERNS = List.of(
            entry("(?i)\\b(developer|unfiltered|god|evil|hacker)\\s+mode\\b", "可疑模式"),
            entry("(?i)\\brepeat\\s+after\\s+me\\b", "重复指令"),
            entry("(?i)\\bnew\\s+(instructions?|rules?)\\s*[:：]", "新指令注入"),
            entry("(开发者|无限制|上帝|黑客)模式", "中文-可疑模式"),
            entry("跟我(重复|读|说)", "中文-重复指令"),
            entry("新(的)?(指令|规则)[:：]", "中文-新指令注入")
    );

    // ──────────────── 间接注入模式 ────────────────

    /** Markdown 链接使用危险 URI（javascript/data/vbscript/file）。 */
    private static final Pattern DANGEROUS_LINK_PATTERN =
            Pattern.compile("(?i)\\[([^\\]]*)\\]\\((javascript|data|vbscript|file):[^)]*\\)");

    /** 图片标签使用危险 URI。 */
    private static final Pattern DANGEROUS_IMAGE_PATTERN =
            Pattern.compile("(?i)!\\[[^\\]]*\\]\\((javascript|data|vbscript):[^)]*\\)");

    /** 危险 HTML 标签（script/iframe/object/embed/svg）。 */
    private static final Pattern DANGEROUS_HTML_PATTERN =
            Pattern.compile("(?i)<(?:script|iframe|object|embed|svg|meta|link)[^>]*>|</(?:script|iframe|object|embed|svg)>");

    /** 事件处理器属性（onerror/onload 等）。 */
    private static final Pattern EVENT_HANDLER_PATTERN =
            Pattern.compile("(?i)\\bon(error|load|click|mouseover|focus|submit|change|input)\\s*=");

    /** HTML 注释（可能包含隐藏指令）。 */
    private static final Pattern HTML_COMMENT_PATTERN =
            Pattern.compile("<!--[\\s\\S]*?-->");

    /** 零宽字符（U+200B/200C/200D/FEFF/2060）。 */
    private static final Pattern ZERO_WIDTH_PATTERN =
            Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF\\u2060]");

    /** 间接注入危险模式列表（regex, 描述）。 */
    private static final List<PatternEntry> INDIRECT_PATTERNS = List.of(
            entry(DANGEROUS_LINK_PATTERN, "Markdown危险链接"),
            entry(DANGEROUS_IMAGE_PATTERN, "图片注入"),
            entry(DANGEROUS_HTML_PATTERN, "危险HTML标签"),
            entry(EVENT_HANDLER_PATTERN, "事件处理器注入")
    );

    // ──────────────── 编码绕过模式 ────────────────

    /** Base64 编码串（长度 ≥ 20，含可选填充）。 */
    private static final Pattern BASE64_PATTERN =
            Pattern.compile("[A-Za-z0-9+/]{20,}={0,2}");

    /** URL 编码序列（连续 ≥ 5 个 %XX）。 */
    private static final Pattern URL_ENCODED_PATTERN =
            Pattern.compile("(?:%[0-9A-Fa-f]{2}){5,}");

    /** Unicode 转义序列（连续 >= 3 个反斜杠u加四位十六进制）。 */
    private static final Pattern UNICODE_ESCAPE_PATTERN =
            Pattern.compile("(?:\\\\u[0-9A-Fa-f]{4}){3,}");

    /** HTML 实体序列（连续 ≥ 3 个 &#xHH; 或 &#DD;）。 */
    private static final Pattern HTML_ENTITY_PATTERN =
            Pattern.compile("(?:&#[xX]?[0-9A-Fa-f]+;){3,}");

    /** 单个 Unicode 转义单元，用于解码。 */
    private static final Pattern SINGLE_UNICODE_ESCAPE =
            Pattern.compile("\\\\u([0-9A-Fa-f]{4})");

    /** 单个 HTML 实体，用于解码。 */
    private static final Pattern SINGLE_HTML_ENTITY =
            Pattern.compile("&#([xX]?)([0-9A-Fa-f]+);");

    private final SafetyProperties properties;

    public PromptInjectionDetector(SafetyProperties properties) {
        this.properties = properties;
    }

    /**
     * 检测输入文本中的 Prompt 注入攻击。
     *
     * @param input 用户输入文本，为 null 或空白时返回安全结果
     * @return 注入检测结果，包含是否恶意、置信度、攻击类型、净化后内容与命中模式列表
     */
    public InjectionDetectionResult detect(String input) {
        if (input == null || input.isBlank()) {
            return new InjectionDetectionResult(false, 0.0, AttackType.NONE,
                    input == null ? "" : input, List.of());
        }

        SafetyProperties.InjectionConfig config = properties.getInjection();
        List<String> matchedPatterns = new ArrayList<>();
        List<AttackType> detectedTypes = new ArrayList<>();
        double confidence = 0.0;

        try {
            // 1. 直接注入检测
            double directConfidence = detectDirect(input, matchedPatterns);
            if (directConfidence > 0) {
                detectedTypes.add(AttackType.DIRECT);
                confidence += directConfidence;
            }

            // 2. 间接注入检测
            double indirectConfidence = detectIndirect(input, matchedPatterns);
            if (indirectConfidence > 0) {
                detectedTypes.add(AttackType.INDIRECT);
                confidence += indirectConfidence;
            }

            // 3. 编码绕过检测（解码后二次检测注入模式）
            if (config.isDetectEncoded()) {
                double encodedConfidence = detectEncoded(input, matchedPatterns);
                if (encodedConfidence > 0) {
                    detectedTypes.add(AttackType.ENCODED);
                    confidence += encodedConfidence;
                }
            }
        } catch (Exception e) {
            log.error("[PromptInjectionDetector] 注入检测发生异常: {}", e.getMessage(), e);
        }

        confidence = Math.min(confidence, 1.0);
        boolean isMalicious = confidence >= config.getConfidenceThreshold();
        AttackType primaryType = determinePrimaryType(detectedTypes);

        String sanitizedContent = config.isSanitize() ? sanitize(input) : input;

        if (isMalicious) {
            log.warn("[PromptInjectionDetector] 检测到 Prompt 注入，置信度={}, 类型={}, 命中={}",
                    String.format("%.2f", confidence), primaryType, matchedPatterns);
        } else if (confidence > 0) {
            log.debug("[PromptInjectionDetector] 检测到可疑模式但未达阈值，置信度={}, 命中={}",
                    String.format("%.2f", confidence), matchedPatterns);
        }

        return new InjectionDetectionResult(isMalicious, confidence, primaryType,
                sanitizedContent, List.copyOf(matchedPatterns));
    }

    // ──────────────── 直接注入检测 ────────────────

    /**
     * 检测直接注入模式，按严重度分级累加置信度。
     */
    private double detectDirect(String input, List<String> matchedPatterns) {
        double confidence = 0.0;
        confidence += matchPatterns(input, DIRECT_HIGH_PATTERNS, SEVERITY_HIGH, matchedPatterns);
        confidence += matchPatterns(input, DIRECT_MEDIUM_PATTERNS, SEVERITY_MEDIUM, matchedPatterns);
        confidence += matchPatterns(input, DIRECT_LOW_PATTERNS, SEVERITY_LOW, matchedPatterns);
        return confidence;
    }

    /**
     * 在输入中匹配模式列表，命中时累加置信度并记录描述。
     *
     * @param input          输入文本
     * @param patterns       模式列表
     * @param severityPerHit 每次命中的严重度
     * @param matchedPatterns 命中描述收集器
     * @return 累加置信度
     */
    private double matchPatterns(String input, List<PatternEntry> patterns,
                                 double severityPerHit, List<String> matchedPatterns) {
        double confidence = 0.0;
        for (PatternEntry entry : patterns) {
            Matcher matcher = entry.regex().matcher(input);
            int hits = 0;
            while (matcher.find()) {
                hits++;
            }
            if (hits > 0) {
                // 同一模式多次命中，仅累加前 2 次以避免置信度爆炸
                int effectiveHits = Math.min(hits, 2);
                confidence += severityPerHit * effectiveHits;
                matchedPatterns.add(entry.description() + (hits > 1 ? "(x" + hits + ")" : ""));
            }
        }
        return confidence;
    }

    // ──────────────── 间接注入检测 ────────────────

    /**
     * 检测间接注入（危险 Markdown/HTML/零宽字符/HTML 注释中的隐藏指令）。
     */
    private double detectIndirect(String input, List<String> matchedPatterns) {
        double confidence = 0.0;

        // 危险 Markdown / HTML / 事件处理器
        confidence += matchPatterns(input, INDIRECT_PATTERNS, SEVERITY_INDIRECT, matchedPatterns);

        // 零宽字符
        if (ZERO_WIDTH_PATTERN.matcher(input).find()) {
            confidence += SEVERITY_INDIRECT;
            matchedPatterns.add("零宽字符隐藏内容");
        }

        // HTML 注释中是否嵌入注入指令
        Matcher commentMatcher = HTML_COMMENT_PATTERN.matcher(input);
        while (commentMatcher.find()) {
            String comment = commentMatcher.group();
            if (containsDirectInjection(comment)) {
                confidence += SEVERITY_INDIRECT;
                matchedPatterns.add("HTML注释中隐藏指令");
                break;
            }
        }

        return confidence;
    }

    /**
     * 判断文本是否包含任意直接注入模式（用于 HTML 注释二次检查）。
     */
    private boolean containsDirectInjection(String text) {
        return DIRECT_HIGH_PATTERNS.stream().anyMatch(e -> e.regex().matcher(text).find())
                || DIRECT_MEDIUM_PATTERNS.stream().anyMatch(e -> e.regex().matcher(text).find());
    }

    // ──────────────── 编码绕过检测 ────────────────

    /**
     * 检测编码绕过：对 Base64/URL/Unicode/HTML 实体编码内容解码后二次检测注入模式。
     */
    private double detectEncoded(String input, List<String> matchedPatterns) {
        double confidence = 0.0;

        // Base64 解码二次检测
        Matcher b64Matcher = BASE64_PATTERN.matcher(input);
        while (b64Matcher.find()) {
            String decoded = tryDecodeBase64(b64Matcher.group());
            if (decoded != null && containsDirectInjection(decoded)) {
                confidence += SEVERITY_ENCODED;
                matchedPatterns.add("Base64编码隐藏指令");
                break;
            }
        }

        // URL 解码二次检测
        Matcher urlMatcher = URL_ENCODED_PATTERN.matcher(input);
        if (urlMatcher.find()) {
            String decoded = tryDecodeUrl(urlMatcher.group());
            if (decoded != null && containsDirectInjection(decoded)) {
                confidence += SEVERITY_ENCODED;
                matchedPatterns.add("URL编码隐藏指令");
            }
        }

        // Unicode 转义解码二次检测
        Matcher unicodeMatcher = UNICODE_ESCAPE_PATTERN.matcher(input);
        if (unicodeMatcher.find()) {
            String decoded = decodeUnicodeEscapes(unicodeMatcher.group());
            if (containsDirectInjection(decoded)) {
                confidence += SEVERITY_ENCODED;
                matchedPatterns.add("Unicode编码隐藏指令");
            }
        }

        // HTML 实体解码二次检测
        Matcher entityMatcher = HTML_ENTITY_PATTERN.matcher(input);
        if (entityMatcher.find()) {
            String decoded = decodeHtmlEntities(entityMatcher.group());
            if (containsDirectInjection(decoded)) {
                confidence += SEVERITY_ENCODED;
                matchedPatterns.add("HTML实体编码隐藏指令");
            }
        }

        return confidence;
    }

    /**
     * 尝试 Base64 解码，失败返回 null。
     */
    private String tryDecodeBase64(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            // 仅当解码结果包含可读字符时才视为有效
            int printable = 0;
            for (int i = 0; i < Math.min(decoded.length(), 50); i++) {
                if (decoded.charAt(i) >= 0x20) {
                    printable++;
                }
            }
            return printable > decoded.length() / 2 ? decoded : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * URL 解码（%XX → 字符）。
     */
    private String tryDecodeUrl(String encoded) {
        try {
            return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解码 Unicode 转义序列为对应字符。
     */
    private String decodeUnicodeEscapes(String input) {
        Matcher matcher = SINGLE_UNICODE_ESCAPE.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            char c = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(c)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解码 HTML 实体（&#xHH; / &#DD; → 字符）。
     */
    private String decodeHtmlEntities(String input) {
        Matcher matcher = SINGLE_HTML_ENTITY.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String hexFlag = matcher.group(1);
            String value = matcher.group(2);
            int codePoint = hexFlag.isEmpty()
                    ? Integer.parseInt(value, 10)
                    : Integer.parseInt(value, 16);
            try {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) codePoint)));
            } catch (IllegalArgumentException e) {
                matcher.appendReplacement(sb, matcher.group());
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ──────────────── 净化处理 ────────────────

    /**
     * 对输入进行净化：移除/中和检测到的攻击模式。
     *
     * <ul>
     *   <li>危险 Markdown 链接：仅保留链接文本</li>
     *   <li>危险 HTML 标签与事件处理器：移除</li>
     *   <li>零宽字符：移除</li>
     *   <li>直接注入短语：替换为 [FILTERED]</li>
     * </ul>
     */
    private String sanitize(String input) {
        String result = input;
        // 危险链接 → 保留文本
        result = DANGEROUS_LINK_PATTERN.matcher(result).replaceAll("$1");
        // 危险图片 → 移除
        result = DANGEROUS_IMAGE_PATTERN.matcher(result).replaceAll("[图片已移除]");
        // 危险 HTML 标签 → 移除
        result = DANGEROUS_HTML_PATTERN.matcher(result).replaceAll("");
        // 事件处理器 → 移除
        result = EVENT_HANDLER_PATTERN.matcher(result).replaceAll("");
        // HTML 注释 → 移除
        result = HTML_COMMENT_PATTERN.matcher(result).replaceAll("");
        // 零宽字符 → 移除
        result = ZERO_WIDTH_PATTERN.matcher(result).replaceAll("");
        // 直接注入短语 → [FILTERED]
        for (PatternEntry entry : DIRECT_HIGH_PATTERNS) {
            result = entry.regex().matcher(result).replaceAll("[FILTERED]");
        }
        return result;
    }

    // ──────────────── 辅助方法 ────────────────

    /**
     * 根据检测到的类型列表确定主要攻击类型（优先级 DIRECT > INDIRECT > ENCODED）。
     */
    private AttackType determinePrimaryType(List<AttackType> detectedTypes) {
        if (detectedTypes.isEmpty()) {
            return AttackType.NONE;
        }
        if (detectedTypes.contains(AttackType.DIRECT)) {
            return AttackType.DIRECT;
        }
        if (detectedTypes.contains(AttackType.INDIRECT)) {
            return AttackType.INDIRECT;
        }
        return AttackType.ENCODED;
    }

    // ──────────────── 模式条目与结果类型 ────────────────

    /** 创建模式条目（regex 字符串形式，自动预编译为大小写不敏感）。 */
    private static PatternEntry entry(String regex, String description) {
        return new PatternEntry(Pattern.compile(regex), description);
    }

    /** 创建模式条目（已编译 Pattern 形式）。 */
    private static PatternEntry entry(Pattern regex, String description) {
        return new PatternEntry(regex, description);
    }

    /**
     * 注入模式条目：预编译正则 + 描述。
     *
     * @param regex       预编译正则
     * @param description 模式描述（用于命中记录）
     */
    private record PatternEntry(Pattern regex, String description) {
    }

    /**
     * Prompt 注入检测结果。
     *
     * @param isMalicious       是否判定为恶意注入
     * @param confidence        置信度（0.0-1.0）
     * @param attackType        主要攻击类型
     * @param sanitizedContent  净化后的内容
     * @param matchedPatterns   命中的模式描述列表
     */
    public record InjectionDetectionResult(
            boolean isMalicious,
            double confidence,
            AttackType attackType,
            String sanitizedContent,
            List<String> matchedPatterns
    ) {
        public InjectionDetectionResult {
            matchedPatterns = matchedPatterns == null ? List.of() : List.copyOf(matchedPatterns);
        }

        /** 是否检测到任何可疑模式（即便未达判定阈值）。 */
        public boolean hasSuspicion() {
            return confidence > 0;
        }
    }
}
