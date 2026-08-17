package com.contentops.common.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 幻觉防护服务（生产级幻觉控制策略集合）。
 *
 * <p>面试实战洞察（P1 高频）：<b>「实测效果：可从 20%+ 幻觉率压到 5% 以下」</b>。
 * 本服务将该洞察落地为四类可组合、可观测的幻觉控制策略，贯穿 LLM 生成的
 * 「解码前参数治理 → 生成后事实核验 → 坏案回流」全链路：
 *
 * <h3>策略一：解码控制（Decoding Control）</h3>
 * <p>通过 {@link #configureDecoding(DecodingConfig)} 按任务类型输出最优生成参数：
 * <ul>
 *   <li>事实型 / 摘要任务：{@code temperature=0}、{@code top_p=0.9}，最大程度抑制采样随机性</li>
 *   <li>结构化输出任务：{@code temperature=0} + JSON Schema 约束解码，
 *       对「格式幻觉」近乎 100% 有效</li>
 *   <li>翻译任务：{@code temperature=0.2}、{@code top_p=0.9}，保真与流畅平衡</li>
 *   <li>创意任务：{@code temperature=0.7}、{@code top_p=0.9}，保留适度创造性</li>
 *   <li>所有任务 {@code top_p} 一律钳制到 0.8–0.9 区间</li>
 * </ul>
 *
 * <h3>策略二：引用核验（Citation Verification）</h3>
 * <p>{@link #verifyCitations(String, List)} 从生成文本中抽取事实声明（含数字 / 百分比 /
 * 年份 / 研究引用的句子），逐一与检索来源做关键词覆盖度比对：
 * <ul>
 *   <li>无来源支撑的声明 → {@link HallucinationType#UNSUPPORTED_CLAIM}</li>
 *   <li>与来源否定极性矛盾 → {@link HallucinationType#CONTRADICTS_SOURCE}</li>
 *   <li>文本中的数字未在来源中出现 → {@link HallucinationType#WRONG_NUMBERS}</li>
 * </ul>
 *
 * <h3>策略三：事实落地检查（Fact-grounding Check）</h3>
 * <p>{@link #checkHallucination(HallucinationCheckRequest)} 在引用核验基础上叠加：
 * 主题漂移（{@link HallucinationType#TOPIC_DRIFT}）、捏造实体
 * （{@link HallucinationType#FABRICATED_ENTITY}）、虚构引用
 * （{@link HallucinationType#HALLUCINATED_CITATION}）、格式违规
 * （{@link HallucinationType#FORMAT_VIOLATION}），综合评定风险等级与是否阻断。
 *
 * <h3>策略四：坏案回流（Hallucination Case Feedback Loop）</h3>
 * <p>{@link #recordHallucinationCase(String, String, String, HallucinationType)} 收集线上
 * 坏案（query / output / issue / type），在内存中维护有界队列与类型分布，
 * 供后续回灌评估集 / 微调负样本。{@link #getHallucinationStats()} 输出累计统计快照。
 *
 * <h3>线程安全</h3>
 * <p>所有统计计数使用 {@link AtomicInteger} / {@link AtomicLong} 与
 * {@link ConcurrentHashMap} / {@link ConcurrentLinkedQueue}，可在多线程并发调用。
 *
 * <h3>Java 21 特性</h3>
 * <ul>
 *   <li>{@code record} 承载全部 DTO（{@link DecodingConfig}、{@link DecodingResult} 等）</li>
 *   <li>{@code sealed} 接口 {@link DecodingStrategy} 建模解码策略层级</li>
 *   <li>pattern matching for switch：{@link #describe(DecodingStrategy)} 对 sealed 层级做穷尽匹配</li>
 * </ul>
 *
 * @see OutputGuardrail
 * @see SafetyGuardService
 */
@Slf4j
@Component
public class HallucinationGuardService {

    // ──────────────── 阈值与上限常量 ────────────────

    /** 声明与来源的关键词覆盖度低于此值视为「无来源支撑」。 */
    private static final double SUPPORT_THRESHOLD = 0.3;
    /** 触发「与来源矛盾」判定所需的最小覆盖度（覆盖度足够高才比对否定极性）。 */
    private static final double CONTRADICTION_MIN_OVERLAP = 0.4;
    /** 生成内容与查询的关键词覆盖度低于此值视为「主题漂移」。 */
    private static final double TOPIC_DRIFT_THRESHOLD = 0.15;
    /** 单句作为「声明」的最小长度（字符）。 */
    private static final int MIN_CLAIM_LENGTH = 6;
    /** WRONG_NUMBERS 单次检查最多上报条数，避免数字密集文本刷屏。 */
    private static final int MAX_NUMBER_ISSUES = 5;
    /** FABRICATED_ENTITY 单次检查最多上报条数。 */
    private static final int MAX_ENTITY_ISSUES = 5;
    /** UNSUPPORTED_CLAIM 单次检查最多上报条数。 */
    private static final int MAX_UNSUPPORTED_CLAIMS = 5;
    /** 内存中保留的坏案上限（超出则丢弃最旧）。 */
    private static final int MAX_FEEDBACK_CASES = 1000;
    /** 风险得分累加缩放因子（double → long，避免浮点 CAS）。 */
    private static final long RISK_SCORE_SCALE = 10_000L;

    // ──────────────── 预编译正则 ────────────────

    /** 句子切分（中英文标点 + 换行），用于声明抽取与来源句子比对。 */
    private static final Pattern SENTENCE_PATTERN =
            Pattern.compile("[^。！？.!?\\n]+(?:[。！？.!?]|\\n|$)");

    /** 事实声明标志：百分比 / 中文数量断言 / 年份 / 2 位以上数字 / 研究引用（中英文）。 */
    private static final Pattern FACT_CLAIM_PATTERN = Pattern.compile(
            "(\\d+(\\.\\d+)?%)"                                         // 百分比
                    + "|(超过\\d+|高达\\d+|约\\d+|大约\\d+|将近\\d+|近\\d+万?)" // 中文数量断言
                    + "|(\\d{4}年)"                                       // 年份
                    + "|(\\d{2,}(\\.\\d+)?)"                              // 2 位以上数字
                    + "|(据(统计|研究|调查|报道|数据))"                      // 中文研究引用
                    + "|(?i)(according\\s+to|studies\\s+show|research\\s+indicates"
                    + "|data\\s+shows|statistics\\s+show)");             // 英文研究引用

    /** 需要核验的数字：百分比 或 2 位以上整数/小数。 */
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("\\d+(?:\\.\\d+)?%|\\d{2,}(?:\\.\\d+)?");

    /** 英文单词（用于关键词集合构建）。 */
    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z][A-Za-z'-]*");

    /** 连续中文片段（用于构建 2-gram 关键词）。 */
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]+");

    /** 引号 / 书名号包裹的实体（双引号 / 「」 / 《》）。 */
    private static final Pattern QUOTED_ENTITY_PATTERN =
            Pattern.compile("\"([^\"]{2,30})\"|「([^」]{2,30})」|《([^》]{2,30})》");

    /** 英文首字母大写多词实体（2–4 词）。 */
    private static final Pattern CAPITALIZED_ENTITY_PATTERN =
            Pattern.compile("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,3}\\b");

    /** 方括号数字引用，如 [1]、[12]。 */
    private static final Pattern BRACKET_CITATION_PATTERN = Pattern.compile("\\[(\\d{1,3})\\]");

    /** 通用引用标记（数字引用 / 据参见 / 来源：）。 */
    private static final Pattern CITATION_MARKER_PATTERN =
            Pattern.compile("\\[\\d{1,3}\\]|（(?:据|参见|见)[^）]*）|\\((?:据|参见|见)[^)]*\\)|来源[:：]");

    // ──────────────── 停用词 / 否定词 ────────────────

    /** 英文停用词（关键词构建时剔除）。 */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "to", "of", "in", "on", "for", "and", "or", "but", "with", "as", "by",
            "at", "from", "that", "this", "it", "its", "has", "have", "had",
            "do", "does", "did", "not", "no", "can", "will", "would", "could",
            "should", "may", "might", "must", "shall", "you", "we", "they",
            "he", "she");

    /** 中文停用字 / 停用 2-gram（关键词构建时剔除）。 */
    private static final Set<String> CJK_STOPCHARS = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "也", "都", "就", "还", "又",
            "才", "把", "被", "让", "使", "对", "向", "从", "为", "以", "于", "及",
            "其", "之", "而", "则", "若", "如", "因", "由", "给", "到", "上", "下",
            "中", "里", "等", "们", "吧", "呢", "吗", "啊", "哦", "嗯");

    /** 否定词列表（中英文），用于矛盾极性判定。 */
    private static final List<String> NEGATIONS = List.of(
            "不", "没", "无", "非", "未", "别", "勿", "没有", "并非", "不是",
            "不能", "无法", "not ", "no ", "never", "none", "n't", "cannot");

    /** 英文大写实体常见句首词（避免把句首大写误判为捏造实体）。 */
    private static final Set<String> ENTITY_STARTERS = Set.of(
            "The", "A", "An", "This", "That", "These", "Those", "It", "We", "They",
            "He", "She", "In", "On", "At", "For", "And", "But", "Or", "When",
            "While", "If", "Because", "Since");

    // ──────────────── 统计计数器（线程安全） ────────────────

    private final AtomicLong totalChecks = new AtomicLong();
    private final AtomicInteger lowRiskCount = new AtomicInteger();
    private final AtomicInteger mediumRiskCount = new AtomicInteger();
    private final AtomicInteger highRiskCount = new AtomicInteger();
    private final AtomicInteger criticalRiskCount = new AtomicInteger();
    /** 累计风险得分 × {@link #RISK_SCORE_SCALE}（long 累加，避免浮点 CAS）。 */
    private final AtomicLong riskScoreSumScaled = new AtomicLong();
    private final AtomicLong feedbackCasesCount = new AtomicLong();
    private final ConcurrentHashMap<HallucinationType, AtomicInteger> typeDistribution =
            new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<HallucinationCase> feedbackCases =
            new ConcurrentLinkedQueue<>();

    // ════════════════════════════════════════════════════════════════
    // 策略一：解码控制
    // ════════════════════════════════════════════════════════════════

    /**
     * 按任务类型配置最优解码参数（幻觉抑制导向）。
     *
     * <p>规则（与面试洞察一致）：
     * <ul>
     *   <li>FACTUAL / SUMMARIZATION：{@code temperature=0}、{@code top_p=0.9}</li>
     *   <li>STRUCTURED_OUTPUT：{@code temperature=0} + JSON Schema 约束解码</li>
     *   <li>TRANSLATION：{@code temperature=0.2}、{@code top_p=0.9}</li>
     *   <li>CREATIVE：{@code temperature=0.7}、{@code top_p=0.9}（保留创造性）</li>
     * </ul>
     * <p>对于事实型 / 摘要 / 结构化 / 翻译任务，请求的 temperature 会被向下钳制到策略上限
     * （即「只能更保守，不能更激进」）；创意任务则尊重请求值。所有任务 top_p 钳制到 0.8–0.9。
     *
     * @param config 请求的解码配置（可为 null，按创意任务默认处理）
     * @return 优化后的解码参数与策略说明
     */
    public DecodingResult configureDecoding(DecodingConfig config) {
        DecodingConfig effective = config == null
                ? new DecodingConfig(0.7, 0.9, null, false, null, TaskType.CREATIVE)
                : config;

        DecodingStrategy strategy = selectStrategy(effective.taskType(), effective);

        // 温度：非创意任务向下钳制到策略上限；创意任务尊重请求值（限定 [0,1]）
        double finalTemp;
        if (effective.taskType() == TaskType.CREATIVE) {
            finalTemp = clamp(effective.temperature(), 0.0, 1.0);
        } else {
            finalTemp = Math.max(0.0, Math.min(effective.temperature(), strategy.temperature()));
        }

        // top_p 一律钳制到 0.8–0.9
        double finalTopP = clamp(effective.topP(), 0.8, 0.9);

        // top_k：请求优先，否则取策略默认
        Integer finalTopK = effective.topK() != null ? effective.topK() : strategy.topK();

        // JSON Schema 约束：请求显式开启 或 策略要求（结构化输出）即启用
        boolean constrained = effective.jsonSchemaConstrained() || strategy.constrained();
        String schema = effective.jsonSchema();

        String explanation = describe(strategy);
        if (constrained) {
            explanation += "；已启用 JSON Schema 约束解码（近乎 100% 消除格式幻觉）";
            if (schema == null || schema.isBlank()) {
                explanation += "（注意：未提供 schema，调用方需补充）";
            }
        }

        log.debug("[HallucinationGuard] 解码配置 taskType={}, temp={}, topP={}, topK={}, constrained={}",
                effective.taskType(), finalTemp, finalTopP, finalTopK, constrained);

        return new DecodingResult(finalTemp, finalTopP, finalTopK, schema, explanation, constrained);
    }

    /**
     * 按 {@link TaskType} 选择解码策略；taskType 为 null 时回退到尊重请求参数的默认策略。
     */
    private DecodingStrategy selectStrategy(TaskType taskType, DecodingConfig config) {
        if (taskType == null) {
            return new DefaultStrategy(config.temperature(), config.topP(),
                    config.topK(), config.jsonSchemaConstrained());
        }
        return switch (taskType) {
            case FACTUAL -> new FactualStrategy();
            case CREATIVE -> new CreativeStrategy();
            case STRUCTURED_OUTPUT -> new StructuredOutputStrategy();
            case SUMMARIZATION -> new SummarizationStrategy();
            case TRANSLATION -> new TranslationStrategy();
        };
    }

    /**
     * 使用 pattern matching for switch 对 sealed {@link DecodingStrategy} 层级做穷尽匹配，
     * 生成人类可读的策略说明。
     */
    private String describe(DecodingStrategy strategy) {
        return switch (strategy) {
            case FactualStrategy ignored ->
                    "事实型/检索任务：temperature=0、top_p=0.9，最大程度抑制采样随机性以规避事实幻觉";
            case CreativeStrategy ignored ->
                    "创意型任务：temperature=0.7、top_p=0.9，保留适度创造性";
            case StructuredOutputStrategy ignored ->
                    "结构化输出任务：temperature=0 + JSON Schema 约束解码，近乎 100% 消除格式幻觉";
            case SummarizationStrategy ignored ->
                    "摘要任务：temperature=0、top_p=0.9，确保忠实于原文、不引入额外事实";
            case TranslationStrategy ignored ->
                    "翻译任务：temperature=0.2、top_p=0.9，在保真与流畅间取得平衡";
            case DefaultStrategy d ->
                    String.format("默认策略：temperature=%.2f、top_p=%.2f", d.temperature(), d.topP());
        };
    }

    /** 将 value 钳制到 [min, max] 区间。 */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ════════════════════════════════════════════════════════════════
    // 策略二：引用核验
    // ════════════════════════════════════════════════════════════════

    /**
     * 引用核验：检查生成文本中的事实声明是否可回溯到检索来源。
     *
     * <p>流程：
     * <ol>
     *   <li>抽取含事实声明标志的句子作为「声明」</li>
     *   <li>对每个声明计算与来源的关键词覆盖度</li>
     *   <li>覆盖度低于 {@link #SUPPORT_THRESHOLD} → {@link HallucinationType#UNSUPPORTED_CLAIM}</li>
     *   <li>覆盖度足够高但否定极性与来源矛盾 → {@link HallucinationType#CONTRADICTS_SOURCE}</li>
     *   <li>文本中数字未在任一来源出现 → {@link HallucinationType#WRONG_NUMBERS}</li>
     * </ol>
     *
     * @param generatedText 生成文本
     * @param sourceContexts 检索到的来源上下文（可为 null 或空）
     * @return 检测到的问题列表（无问题则返回空列表）
     */
    public List<HallucinationIssue> verifyCitations(String generatedText, List<String> sourceContexts) {
        List<HallucinationIssue> issues = new ArrayList<>();
        if (generatedText == null || generatedText.isBlank()) {
            return issues;
        }
        List<String> sources = sourceContexts == null ? List.of() : sourceContexts;
        String combinedSources = String.join(" ", sources);
        Set<String> sourceKeywords = buildKeywordSet(combinedSources);
        List<String> sourceSentences = splitSentences(combinedSources);

        // 1. 声明级来源支撑 / 矛盾核验
        int unsupportedCount = 0;
        for (ClaimSpan claim : extractClaims(generatedText)) {
            Set<String> claimKeywords = buildKeywordSet(claim.text());
            if (claimKeywords.isEmpty()) {
                continue;
            }
            double overlap = overlapRatio(claimKeywords, sourceKeywords);

            if (sources.isEmpty()) {
                if (unsupportedCount < MAX_UNSUPPORTED_CLAIMS) {
                    issues.add(new HallucinationIssue(HallucinationType.UNSUPPORTED_CLAIM,
                            "声明无任何来源支撑", claim.text(), claim.start()));
                    unsupportedCount++;
                }
                continue;
            }
            if (overlap < SUPPORT_THRESHOLD && unsupportedCount < MAX_UNSUPPORTED_CLAIMS) {
                issues.add(new HallucinationIssue(HallucinationType.UNSUPPORTED_CLAIM,
                        String.format("声明缺乏来源支撑（关键词覆盖度 %.0f%%）", overlap * 100),
                        claim.text(), claim.start()));
                unsupportedCount++;
            }
            // 矛盾判定：覆盖度足够高时比对否定极性
            if (overlap >= CONTRADICTION_MIN_OVERLAP && hasContradiction(claim.text(), sourceSentences)) {
                issues.add(new HallucinationIssue(HallucinationType.CONTRADICTS_SOURCE,
                        "声明与来源矛盾（否定极性不一致）", claim.text(), claim.start()));
            }
        }

        // 2. 数字核验
        issues.addAll(detectWrongNumbers(generatedText, sources));

        return issues;
    }

    /**
     * 数字核验：提取文本中的数字，未在任一来源中出现则标记 WRONG_NUMBERS。
     * 来源为空时跳过（无法核验，避免噪音）。
     */
    private List<HallucinationIssue> detectWrongNumbers(String text, List<String> sources) {
        List<HallucinationIssue> issues = new ArrayList<>();
        if (sources.isEmpty()) {
            return issues;
        }
        Matcher m = NUMBER_PATTERN.matcher(text);
        int count = 0;
        while (m.find() && count < MAX_NUMBER_ISSUES) {
            String num = m.group();
            boolean found = false;
            for (String s : sources) {
                if (s != null && s.contains(num)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                issues.add(new HallucinationIssue(HallucinationType.WRONG_NUMBERS,
                        "数字未在来源中出现: " + num, num, m.start()));
                count++;
            }
        }
        return issues;
    }

    /**
     * 矛盾判定（启发式）：在来源句子中找到与声明覆盖度最高的一句，
     * 若覆盖度 ≥ {@link #CONTRADICTION_MIN_OVERLAP} 且二者否定极性相反，则判定矛盾。
     */
    private boolean hasContradiction(String claim, List<String> sourceSentences) {
        boolean claimNeg = containsNegation(claim);
        Set<String> claimKw = buildKeywordSet(claim);
        double bestOverlap = 0.0;
        boolean bestNeg = false;
        for (String s : sourceSentences) {
            double o = overlapRatio(claimKw, buildKeywordSet(s));
            if (o > bestOverlap) {
                bestOverlap = o;
                bestNeg = containsNegation(s);
            }
        }
        return bestOverlap >= CONTRADICTION_MIN_OVERLAP && claimNeg != bestNeg;
    }

    // ════════════════════════════════════════════════════════════════
    // 策略三：事实落地检查（综合）
    // ════════════════════════════════════════════════════════════════

    /**
     * 综合幻觉检查：在引用核验基础上叠加主题漂移 / 捏造实体 / 虚构引用 / 格式违规检测，
     * 综合评定风险等级、风险置信度与是否应阻断，并更新统计计数。
     *
     * @param request 幻觉检查请求（生成文本 + 检索上下文 + 查询 + 任务类型）
     * @return 检查结果（风险等级、置信度、问题列表、是否阻断）
     */
    public HallucinationCheckResult checkHallucination(HallucinationCheckRequest request) {
        if (request == null || request.generatedText() == null || request.generatedText().isBlank()) {
            recordStat(HallucinationRiskLevel.LOW, 0.0, List.of());
            return new HallucinationCheckResult(HallucinationRiskLevel.LOW, 0.0, List.of(), false);
        }

        List<String> contexts = request.retrievedContexts();
        String text = request.generatedText();

        List<HallucinationIssue> issues = new ArrayList<>(verifyCitations(text, contexts));
        issues.addAll(detectTopicDrift(request.query(), text));
        issues.addAll(detectFabricatedEntities(text, contexts));
        issues.addAll(detectHallucinatedCitations(text, contexts));
        if (request.taskType() == TaskType.STRUCTURED_OUTPUT) {
            issues.addAll(detectFormatViolation(text));
        }

        HallucinationRiskLevel riskLevel = computeRiskLevel(issues);
        double confidenceScore = computeConfidence(issues);
        boolean shouldBlock = riskLevel == HallucinationRiskLevel.HIGH
                || riskLevel == HallucinationRiskLevel.CRITICAL;

        recordStat(riskLevel, confidenceScore, issues);

        if (shouldBlock) {
            log.warn("[HallucinationGuard] 检测到高风险幻觉 riskLevel={}, confidence={}, issues={}",
                    riskLevel, String.format("%.2f", confidenceScore), issues.size());
        } else if (!issues.isEmpty()) {
            log.debug("[HallucinationGuard] 检测到低/中风险幻觉 riskLevel={}, issues={}",
                    riskLevel, issues.size());
        }

        return new HallucinationCheckResult(riskLevel, confidenceScore,
                List.copyOf(issues), shouldBlock);
    }

    /** 主题漂移检测：生成内容与查询的关键词覆盖度过低则标记。 */
    private List<HallucinationIssue> detectTopicDrift(String query, String text) {
        List<HallucinationIssue> issues = new ArrayList<>();
        if (query == null || query.isBlank() || text == null || text.length() < MIN_CLAIM_LENGTH) {
            return issues;
        }
        Set<String> qkw = buildKeywordSet(query);
        Set<String> tkw = buildKeywordSet(text);
        double overlap = overlapRatio(qkw, tkw);
        if (overlap < TOPIC_DRIFT_THRESHOLD) {
            issues.add(new HallucinationIssue(HallucinationType.TOPIC_DRIFT,
                    String.format("生成内容与查询主题偏离（关键词覆盖度 %.0f%%）", overlap * 100),
                    summarize(text), 0));
        }
        return issues;
    }

    /**
     * 捏造实体检测：引号 / 书名号包裹实体、英文首字母大写多词实体，
     * 若未在任一来源中出现则标记 FABRICATED_ENTITY。来源为空时跳过。
     */
    private List<HallucinationIssue> detectFabricatedEntities(String text, List<String> sources) {
        List<HallucinationIssue> issues = new ArrayList<>();
        if (sources == null || sources.isEmpty()) {
            return issues;
        }
        String combined = String.join(" ", sources).toLowerCase(Locale.ROOT);
        int count = 0;

        // 引号 / 书名号实体
        Matcher qm = QUOTED_ENTITY_PATTERN.matcher(text);
        while (qm.find() && count < MAX_ENTITY_ISSUES) {
            String entity = null;
            for (int g = 1; g <= 3; g++) {
                if (qm.group(g) != null) {
                    entity = qm.group(g);
                    break;
                }
            }
            if (entity == null || entity.length() < 2) {
                continue;
            }
            if (!combined.contains(entity.toLowerCase(Locale.ROOT))) {
                issues.add(new HallucinationIssue(HallucinationType.FABRICATED_ENTITY,
                        "疑似捏造实体: " + entity, entity, qm.start()));
                count++;
            }
        }

        // 英文大写多词实体
        Matcher em = CAPITALIZED_ENTITY_PATTERN.matcher(text);
        while (em.find() && count < MAX_ENTITY_ISSUES) {
            String entity = em.group();
            int sp = entity.indexOf(' ');
            String firstWord = sp > 0 ? entity.substring(0, sp) : entity;
            if (ENTITY_STARTERS.contains(firstWord)) {
                continue;
            }
            if (!combined.contains(entity.toLowerCase(Locale.ROOT))) {
                issues.add(new HallucinationIssue(HallucinationType.FABRICATED_ENTITY,
                        "疑似捏造实体: " + entity, entity, em.start()));
                count++;
            }
        }
        return issues;
    }

    /**
     * 虚构引用检测：方括号数字引用超过来源数量，或存在引用标记但无任何来源，
     * 则标记 HALLUCINATED_CITATION。
     */
    private List<HallucinationIssue> detectHallucinatedCitations(String text, List<String> sources) {
        List<HallucinationIssue> issues = new ArrayList<>();
        int sourceCount = sources == null ? 0 : sources.size();

        Matcher cm = BRACKET_CITATION_PATTERN.matcher(text);
        while (cm.find()) {
            int idx = Integer.parseInt(cm.group(1));
            if (idx > sourceCount) {
                issues.add(new HallucinationIssue(HallucinationType.HALLUCINATED_CITATION,
                        "引用了不存在的来源编号 [" + idx + "]", cm.group(), cm.start()));
            }
        }
        if (sourceCount == 0 && CITATION_MARKER_PATTERN.matcher(text).find()) {
            issues.add(new HallucinationIssue(HallucinationType.HALLUCINATED_CITATION,
                    "包含引用标记但未提供任何来源", "引用标记", 0));
        }
        return issues;
    }

    /**
     * 格式违规检测：结构化输出任务期望 JSON，输出不符合 JSON 外形则标记 FORMAT_VIOLATION。
     */
    private List<HallucinationIssue> detectFormatViolation(String text) {
        List<HallucinationIssue> issues = new ArrayList<>();
        String trimmed = text.trim();
        boolean looksJson = (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
        if (!looksJson) {
            issues.add(new HallucinationIssue(HallucinationType.FORMAT_VIOLATION,
                    "结构化输出任务期望 JSON 格式但输出不符合", summarize(trimmed), 0));
        }
        return issues;
    }

    /**
     * 综合风险等级评定：按问题类型严重度与数量分级。
     * <ul>
     *   <li>HIGH 级问题（矛盾 / 捏造实体 / 虚构引用）≥3 → CRITICAL，≥1 → HIGH</li>
     *   <li>MEDIUM 级问题（无支撑声明 / 错误数字）≥3 → HIGH，≥1 → MEDIUM</li>
     *   <li>LOW 级问题（格式违规 / 主题漂移）≥3 → MEDIUM，否则 LOW</li>
     * </ul>
     */
    private HallucinationRiskLevel computeRiskLevel(List<HallucinationIssue> issues) {
        if (issues.isEmpty()) {
            return HallucinationRiskLevel.LOW;
        }
        int high = 0, medium = 0, low = 0;
        for (HallucinationIssue issue : issues) {
            switch (issue.type()) {
                case CONTRADICTS_SOURCE, FABRICATED_ENTITY, HALLUCINATED_CITATION -> high++;
                case UNSUPPORTED_CLAIM, WRONG_NUMBERS -> medium++;
                case FORMAT_VIOLATION, TOPIC_DRIFT -> low++;
            }
        }
        if (high >= 3) {
            return HallucinationRiskLevel.CRITICAL;
        }
        if (high >= 1) {
            return HallucinationRiskLevel.HIGH;
        }
        if (medium >= 3) {
            return HallucinationRiskLevel.HIGH;
        }
        if (medium >= 1) {
            return HallucinationRiskLevel.MEDIUM;
        }
        if (low >= 3) {
            return HallucinationRiskLevel.MEDIUM;
        }
        return HallucinationRiskLevel.LOW;
    }

    /**
     * 风险置信度（0.0–1.0，越高表示幻觉风险越高）：按问题类型加权累加并截断到 1.0。
     */
    private double computeConfidence(List<HallucinationIssue> issues) {
        double score = 0.0;
        for (HallucinationIssue issue : issues) {
            score += switch (issue.type()) {
                case CONTRADICTS_SOURCE, FABRICATED_ENTITY, HALLUCINATED_CITATION -> 0.3;
                case UNSUPPORTED_CLAIM, WRONG_NUMBERS -> 0.2;
                case FORMAT_VIOLATION, TOPIC_DRIFT -> 0.1;
            };
        }
        return Math.min(score, 1.0);
    }

    // ════════════════════════════════════════════════════════════════
    // 策略四：坏案回流 + 统计
    // ════════════════════════════════════════════════════════════════

    /**
     * 记录一例幻觉坏案（反馈回流入口）。
     *
     * <p>坏案在内存中保留最近 {@link #MAX_FEEDBACK_CASES} 条（FIFO），并累计类型分布，
     * 供后续回灌评估集 / 微调负样本。
     *
     * @param query  原始查询
     * @param output 模型输出
     * @param issue  问题描述
     * @param type   幻觉类型
     */
    public void recordHallucinationCase(String query, String output, String issue, HallucinationType type) {
        HallucinationCase cas = new HallucinationCase(query, output, issue, type, System.currentTimeMillis());
        feedbackCases.add(cas);
        while (feedbackCases.size() > MAX_FEEDBACK_CASES) {
            feedbackCases.poll();
        }
        feedbackCasesCount.incrementAndGet();
        typeDistribution.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
        log.info("[HallucinationGuard] 记录幻觉坏案 type={}, issue={}, query摘要={}",
                type, issue, summarize(query));
    }

    /**
     * 获取幻觉检测累计统计快照。
     *
     * <p>注意：{@code highRiskCount} 汇总 HIGH 与 CRITICAL 两级（统计口径仅三档）。
     *
     * @return 统计快照
     */
    public HallucinationStats getHallucinationStats() {
        long total = totalChecks.get();
        int high = highRiskCount.get() + criticalRiskCount.get();
        int medium = mediumRiskCount.get();
        int low = lowRiskCount.get();
        double avg = total == 0 ? 0.0
                : (riskScoreSumScaled.get() / (double) RISK_SCORE_SCALE) / total;

        Map<HallucinationType, Integer> dist = new LinkedHashMap<>();
        for (HallucinationType t : HallucinationType.values()) {
            AtomicInteger c = typeDistribution.get(t);
            dist.put(t, c == null ? 0 : c.get());
        }
        return new HallucinationStats((int) total, high, medium, low, avg,
                (int) feedbackCasesCount.get(), Map.copyOf(dist));
    }

    /** 更新统计计数（线程安全）。 */
    private void recordStat(HallucinationRiskLevel level, double confidence, List<HallucinationIssue> issues) {
        totalChecks.incrementAndGet();
        riskScoreSumScaled.addAndGet(Math.round(confidence * RISK_SCORE_SCALE));
        switch (level) {
            case CRITICAL -> criticalRiskCount.incrementAndGet();
            case HIGH -> highRiskCount.incrementAndGet();
            case MEDIUM -> mediumRiskCount.incrementAndGet();
            case LOW -> lowRiskCount.incrementAndGet();
        }
        for (HallucinationIssue issue : issues) {
            typeDistribution.computeIfAbsent(issue.type(), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 文本分析辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 抽取事实声明：切分句子后保留含 {@link #FACT_CLAIM_PATTERN} 标志的句子。
     *
     * @return 声明片段列表（含文本与起始位置）
     */
    private List<ClaimSpan> extractClaims(String text) {
        List<ClaimSpan> claims = new ArrayList<>();
        Matcher sm = SENTENCE_PATTERN.matcher(text);
        while (sm.find()) {
            String sentence = sm.group().trim();
            if (sentence.length() < MIN_CLAIM_LENGTH) {
                continue;
            }
            if (FACT_CLAIM_PATTERN.matcher(sentence).find()) {
                claims.add(new ClaimSpan(sentence, sm.start()));
            }
        }
        return claims;
    }

    /** 切分句子（中英文标点 + 换行）。 */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }
        Matcher sm = SENTENCE_PATTERN.matcher(text);
        while (sm.find()) {
            String s = sm.group().trim();
            if (!s.isEmpty()) {
                sentences.add(s);
            }
        }
        return sentences;
    }

    /**
     * 构建关键词集合：英文单词（长度 ≥2、非停用词）+ 中文 2-gram（剔除停用 2-gram）。
     */
    private Set<String> buildKeywordSet(String text) {
        Set<String> keywords = new HashSet<>();
        if (text == null || text.isBlank()) {
            return keywords;
        }
        Matcher wm = WORD_PATTERN.matcher(text);
        while (wm.find()) {
            String w = wm.group().toLowerCase(Locale.ROOT);
            if (w.length() >= 2 && !STOPWORDS.contains(w)) {
                keywords.add(w);
            }
        }
        Matcher cm = CJK_PATTERN.matcher(text);
        while (cm.find()) {
            String run = cm.group();
            if (run.length() == 1) {
                if (!CJK_STOPCHARS.contains(run)) {
                    keywords.add(run);
                }
            } else {
                for (int i = 0; i < run.length() - 1; i++) {
                    String bg = run.substring(i, i + 2);
                    if (!CJK_STOPCHARS.contains(bg)) {
                        keywords.add(bg);
                    }
                }
            }
        }
        return keywords;
    }

    /** 覆盖度 = |claim ∩ source| / |claim|（声明关键词被来源覆盖的比例）。 */
    private double overlapRatio(Set<String> claim, Set<String> source) {
        if (claim.isEmpty() || source.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String k : claim) {
            if (source.contains(k)) {
                hit++;
            }
        }
        return (double) hit / claim.size();
    }

    /** 文本是否包含否定词。 */
    private static boolean containsNegation(String text) {
        if (text == null) {
            return false;
        }
        for (String n : NEGATIONS) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** 文本摘要（前 40 字符 + 省略号），用于日志与问题描述。 */
    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }

    // ════════════════════════════════════════════════════════════════
    // 枚举
    // ════════════════════════════════════════════════════════════════

    /** 任务类型（决定解码策略与检查项）。 */
    public enum TaskType {
        /** 事实型 / 检索问答 */
        FACTUAL,
        /** 创意生成 */
        CREATIVE,
        /** 结构化输出（JSON 等） */
        STRUCTURED_OUTPUT,
        /** 摘要 */
        SUMMARIZATION,
        /** 翻译 */
        TRANSLATION
    }

    /** 幻觉风险等级（由低到高）。 */
    public enum HallucinationRiskLevel {
        /** 低风险：无问题或仅轻微告警 */
        LOW,
        /** 中风险：无支撑声明 / 错误数字 */
        MEDIUM,
        /** 高风险：矛盾 / 捏造实体 / 虚构引用 */
        HIGH,
        /** 严重风险：多处高风险问题 */
        CRITICAL
    }

    /** 幻觉类型。 */
    public enum HallucinationType {
        /** 无来源支撑的事实声明 */
        UNSUPPORTED_CLAIM,
        /** 与来源矛盾 */
        CONTRADICTS_SOURCE,
        /** 捏造实体（人名 / 机构 / 作品等） */
        FABRICATED_ENTITY,
        /** 数字与来源不符 */
        WRONG_NUMBERS,
        /** 虚构引用（引用不存在的来源） */
        HALLUCINATED_CITATION,
        /** 格式违规（结构化输出不符合 schema） */
        FORMAT_VIOLATION,
        /** 主题漂移 */
        TOPIC_DRIFT
    }

    // ════════════════════════════════════════════════════════════════
    // DTO records
    // ════════════════════════════════════════════════════════════════

    /**
     * 解码配置请求。
     *
     * @param temperature          请求的温度
     * @param topP                 请求的 top_p
     * @param topK                 请求的 top_k（可为 null）
     * @param jsonSchemaConstrained 是否请求 JSON Schema 约束解码
     * @param jsonSchema           JSON Schema 字符串（可为 null）
     * @param taskType             任务类型（可为 null）
     */
    public record DecodingConfig(
            double temperature,
            double topP,
            Integer topK,
            boolean jsonSchemaConstrained,
            String jsonSchema,
            TaskType taskType
    ) {
    }

    /**
     * 优化后的解码结果。
     *
     * @param temperature  优化后温度
     * @param topP         优化后 top_p
     * @param topK         优化后 top_k（可为 null）
     * @param jsonSchema   JSON Schema 字符串（可为 null）
     * @param explanation  策略说明
     * @param constrained  是否启用 JSON Schema 约束解码
     */
    public record DecodingResult(
            double temperature,
            double topP,
            Integer topK,
            String jsonSchema,
            String explanation,
            boolean constrained
    ) {
    }

    /**
     * 幻觉检查请求。
     *
     * @param generatedText    生成文本
     * @param retrievedContexts 检索到的来源上下文（可为 null）
     * @param query            原始查询（可为 null）
     * @param taskType         任务类型（可为 null）
     */
    public record HallucinationCheckRequest(
            String generatedText,
            List<String> retrievedContexts,
            String query,
            TaskType taskType
    ) {
        public HallucinationCheckRequest {
            retrievedContexts = retrievedContexts == null ? List.of() : List.copyOf(retrievedContexts);
        }
    }

    /**
     * 幻觉检查结果。
     *
     * @param riskLevel       风险等级
     * @param confidenceScore 风险置信度（0.0–1.0，越高幻觉风险越高）
     * @param issues          检测到的问题列表
     * @param shouldBlock     是否应阻断输出
     */
    public record HallucinationCheckResult(
            HallucinationRiskLevel riskLevel,
            double confidenceScore,
            List<HallucinationIssue> issues,
            boolean shouldBlock
    ) {
        public HallucinationCheckResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
            riskLevel = riskLevel == null ? HallucinationRiskLevel.LOW : riskLevel;
        }
    }

    /**
     * 单个幻觉问题。
     *
     * @param type          幻觉类型
     * @param description   问题描述
     * @param textSegment   命中文本片段
     * @param startPosition 起始位置（近似）
     */
    public record HallucinationIssue(
            HallucinationType type,
            String description,
            String textSegment,
            int startPosition
    ) {
        public HallucinationIssue {
            type = type == null ? HallucinationType.UNSUPPORTED_CLAIM : type;
            description = description == null ? "" : description;
            textSegment = textSegment == null ? "" : textSegment;
            startPosition = Math.max(0, startPosition);
        }
    }

    /**
     * 幻觉检测累计统计快照。
     *
     * @param totalChecks        总检查次数
     * @param highRiskCount      高风险次数（含 CRITICAL）
     * @param mediumRiskCount    中风险次数
     * @param lowRiskCount       低风险次数
     * @param avgRiskScore       平均风险得分（0.0–1.0）
     * @param feedbackCasesCount 累计坏案数
     * @param typeDistribution   按幻觉类型的问题分布
     */
    public record HallucinationStats(
            int totalChecks,
            int highRiskCount,
            int mediumRiskCount,
            int lowRiskCount,
            double avgRiskScore,
            int feedbackCasesCount,
            Map<HallucinationType, Integer> typeDistribution
    ) {
        public HallucinationStats {
            typeDistribution = typeDistribution == null ? Map.of() : Map.copyOf(typeDistribution);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 内部类型：sealed 解码策略层级 + 声明/坏案记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 解码策略 sealed 接口：每种 {@link TaskType} 对应一个 record 实现，
     * 由 {@link #describe(DecodingStrategy)} 做穷尽 pattern-matching 匹配。
     */
    private sealed interface DecodingStrategy
            permits FactualStrategy, CreativeStrategy, StructuredOutputStrategy,
                    SummarizationStrategy, TranslationStrategy, DefaultStrategy {
        double temperature();

        double topP();

        Integer topK();

        boolean constrained();
    }

    /** 事实型 / 检索任务策略：temperature=0、top_p=0.9。 */
    private record FactualStrategy() implements DecodingStrategy {
        @Override public double temperature() { return 0.0; }
        @Override public double topP() { return 0.9; }
        @Override public Integer topK() { return null; }
        @Override public boolean constrained() { return false; }
    }

    /** 创意任务策略：temperature=0.7、top_p=0.9。 */
    private record CreativeStrategy() implements DecodingStrategy {
        @Override public double temperature() { return 0.7; }
        @Override public double topP() { return 0.9; }
        @Override public Integer topK() { return null; }
        @Override public boolean constrained() { return false; }
    }

    /** 结构化输出策略：temperature=0 + JSON Schema 约束解码。 */
    private record StructuredOutputStrategy() implements DecodingStrategy {
        @Override public double temperature() { return 0.0; }
        @Override public double topP() { return 0.9; }
        @Override public Integer topK() { return null; }
        @Override public boolean constrained() { return true; }
    }

    /** 摘要任务策略：temperature=0、top_p=0.9。 */
    private record SummarizationStrategy() implements DecodingStrategy {
        @Override public double temperature() { return 0.0; }
        @Override public double topP() { return 0.9; }
        @Override public Integer topK() { return null; }
        @Override public boolean constrained() { return false; }
    }

    /** 翻译任务策略：temperature=0.2、top_p=0.9。 */
    private record TranslationStrategy() implements DecodingStrategy {
        @Override public double temperature() { return 0.2; }
        @Override public double topP() { return 0.9; }
        @Override public Integer topK() { return null; }
        @Override public boolean constrained() { return false; }
    }

    /** 默认策略（taskType 为 null 时回退，尊重请求参数）。 */
    private record DefaultStrategy(
            double temperature,
            double topP,
            Integer topK,
            boolean constrained
    ) implements DecodingStrategy {
    }

    /** 抽取到的事实声明片段（文本 + 起始位置）。 */
    private record ClaimSpan(String text, int start) {
    }

    /** 幻觉坏案记录（反馈回流）。 */
    private record HallucinationCase(
            String query,
            String output,
            String issue,
            HallucinationType type,
            long timestamp
    ) {
    }
}
