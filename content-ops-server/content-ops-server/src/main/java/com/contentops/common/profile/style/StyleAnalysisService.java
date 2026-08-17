package com.contentops.common.profile.style;

import com.contentops.common.profile.style.StyleProfile.ContentStyle;
import com.contentops.common.profile.style.StyleProfile.CoverTone;
import com.contentops.common.profile.style.StyleProfile.EmotionalTendency;
import com.contentops.common.profile.style.StyleProfile.EndingMode;
import com.contentops.common.profile.style.StyleProfile.IllustrationStyle;
import com.contentops.common.profile.style.StyleProfile.LanguageStyle;
import com.contentops.common.profile.style.StyleProfile.LayoutDensity;
import com.contentops.common.profile.style.StyleProfile.OpeningMode;
import com.contentops.common.profile.style.StyleProfile.ParagraphStructure;
import com.contentops.common.profile.style.StyleProfile.StructureStyle;
import com.contentops.common.profile.style.StyleProfile.TitleStyle;
import com.contentops.common.profile.style.StyleProfile.VisualStyle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风格特征提取服务（P0 改造核心）。
 *
 * <p>负责从单篇或多篇内容中提取风格特征，生成 {@link StyleProfile}。提供两条分析通路：
 * <ul>
 *   <li><b>启发式分析</b>（{@link #analyzeStyle}）—— 基于正则与词典的规则匹配，
 *       <b>不依赖任何外部 API</b>，保证核心功能在 LLM 不可用时仍可用</li>
 *   <li><b>LLM 分析</b>（{@link #analyzeStyleWithLLM}）—— 调用 {@link ChatModel} 提取
 *       更深层的风格特征（主观/视觉等），ChatModel 不可用时自动降级到启发式</li>
 * </ul>
 *
 * <h3>启发式分析规则</h3>
 * <ul>
 *   <li>句式分析：按句号/问号/感叹号分句，统计长句(>30字)/短句(<15字)/问句比例</li>
 *   <li>用词复杂度：按领域词典匹配专业术语密度，无词典时用生僻字比例估算</li>
 *   <li>口语化程度：语气词（啊/呢/吧/哈/呀/哦）+ 感叹号比例 + 第一人称使用频率</li>
 *   <li>开头模式检测：首段是否含故事/数据/观点/提问</li>
 *   <li>情感倾向：正面词 vs 负面词比例</li>
 *   <li>标题风格：正则匹配数字开头/疑问句/感叹句</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <p>所有 LLM 相关路径均包含降级：ChatModel 不可用、调用异常、JSON 解析失败时，
 * 一律回退到 {@link #analyzeStyle} 的启发式结果，确保服务永不因 LLM 故障而中断。
 *
 * @see StyleProfile
 * @see StyleProfileProperties
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StyleAnalysisService {

    private final StyleProfileProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════
    //  词典与正则常量
    // ════════════════════════════════════════════════════════════════

    /** 语气词集合（口语化检测）。 */
    private static final Set<String> PARTICLES = Set.of("啊", "呢", "吧", "哈", "呀", "哦", "嘛", "哇", "啦", "哎", "噢");

    /** 第一人称标记（口语化与个人经历检测）。 */
    private static final Set<String> FIRST_PERSON = Set.of("我", "我们", "咱", "咱们", "本人", "笔者");

    /** 观点标记词（观点鲜明度检测）。 */
    private static final Set<String> OPINION_MARKERS = Set.of(
            "认为", "觉得", "应该", "必须", "建议", "在我看来", "我觉得", "个人认为", "个人观点", "我认为", "我的看法", "不要");

    /** 故事型开头标记（时间词 + 人物词）。 */
    private static final Set<String> STORY_TIME = Set.of(
            "今天", "昨天", "前天", "去年", "今年", "前年", "上周", "上个月", "曾经", "那时", "小时候", "前阵子", "最近");
    private static final Set<String> STORY_PERSON = Set.of(
            "我", "他", "她", "朋友", "同事", "妈妈", "爸爸", "孩子", "老板", "客户", "学员", "读者");

    /** 数据型开头标记。 */
    private static final Set<String> DATA_MARKERS = Set.of(
            "数据", "报告", "调查", "统计", "研究", "显示", "表明", "占比", "比例", "万人", "亿");

    /** 递进连接词（段落结构检测）。 */
    private static final Set<String> PROGRESSIVE_MARKERS = Set.of(
            "首先", "其次", "然后", "接着", "最后", "第一", "第二", "第三", "第一步", "第二步", "一方面", "另一方面");

    /** 总结/号召结尾标记。 */
    private static final Set<String> CTA_MARKERS = Set.of(
            "关注", "点赞", "转发", "收藏", "评论", "留言", "扫码", "订阅", "一起", "试试", "动手");
    private static final Set<String> SUMMARY_MARKERS = Set.of(
            "综上", "总之", "总结", "以上就是", "希望", "总的来说", "归根结底");

    /** 案例标记词。 */
    private static final Set<String> CASE_MARKERS = Set.of(
            "案例", "例如", "比如", "举个例子", "我见过", "有个", "曾经遇到", "有个朋友", "举个例子来说");

    /** 幽默标记词/符号。 */
    private static final Set<String> HUMOR_MARKERS = Set.of("哈哈", "233", "笑死", "搞笑", "段子", "梗", "绝了", "离谱");

    /** 正面情感词。 */
    private static final Set<String> POSITIVE_WORDS = Set.of(
            "好", "棒", "优秀", "喜欢", "成功", "开心", "推荐", "值得", "惊喜", "完美", "厉害", "舒服", "幸福", "美好", "赞");

    /** 负面情感词。 */
    private static final Set<String> NEGATIVE_WORDS = Set.of(
            "差", "烂", "失败", "糟糕", "讨厌", "失望", "问题", "坑", "雷", "难受", "痛苦", "麻烦", "危险", "错误", "糟");

    /** 情感型标题标记。 */
    private static final Set<String> EMOTIONAL_TITLE_MARKERS = Set.of(
            "震惊", "绝了", "太", "爆", "哭", "惊呆", "万万没想到", "必看", "刷屏");

    /** 句子分隔正则：句号/问号/感叹号（中英文）。 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！？!?\\n]+");

    /** 数字开头标题正则（如「3 个方法」「10 招」）。 */
    private static final Pattern NUMBER_TITLE = Pattern.compile("^[\\d０-９]+\\s*[个条招步种篇]");

    /** emoji 与符号字符正则（覆盖常见 emoji 区段与中文标点符号区）。 */
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}]");

    /** 问号正则（中英文）。 */
    private static final Pattern QUESTION_MARK = Pattern.compile("[？?]");

    /** 感叹号正则（中英文）。 */
    private static final Pattern EXCLAIM_MARK = Pattern.compile("[！!]");

    /** 数字与百分号正则（数据引用检测）。 */
    private static final Pattern NUMBER_PERCENT = Pattern.compile("\\d+(\\.\\d+)?\\s*[%％]");

    // ════════════════════════════════════════════════════════════════
    //  公共 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 对单篇内容提取风格特征（启发式规则，不依赖 LLM）。
     *
     * <p>分析流程：分句分段 → 语言风格 → 结构风格 → 内容特征 → 视觉风格（默认推断）。
     * 所有特征均归一化，空内容返回空画像。
     *
     * @param content 内容正文（可含标题首行）
     * @return 单篇内容的风格画像（accountId 为空，sampleCount=1）
     */
    public StyleProfile analyzeStyle(String content) {
        if (content == null || content.isBlank()) {
            return StyleProfile.empty();
        }
        String text = content.strip();
        List<String> paragraphs = splitParagraphs(text);
        List<String> sentences = splitSentences(text);
        String title = extractTitle(paragraphs);
        // 开头模式应检测正文首段：若首段恰为标题行，则取下一段作为正文开头
        String firstParagraph = paragraphs.isEmpty() ? text : paragraphs.get(0);
        if (!title.isBlank() && paragraphs.size() > 1 && paragraphs.get(0).equals(title)) {
            firstParagraph = paragraphs.get(1);
        }
        String lastParagraph = paragraphs.isEmpty() ? text : paragraphs.get(paragraphs.size() - 1);
        int totalChars = countChars(text);

        LanguageStyle languageStyle = analyzeLanguageStyle(text, sentences, paragraphs, totalChars);
        StructureStyle structureStyle = analyzeStructureStyle(title, firstParagraph, lastParagraph, paragraphs);
        ContentStyle contentStyle = analyzeContentStyle(text, sentences, totalChars);
        VisualStyle visualStyle = analyzeVisualStyle(title, totalChars);

        return StyleProfile.forContent(languageStyle, structureStyle, contentStyle, visualStyle);
    }

    /**
     * 用 LLM 提取更深层的风格特征。
     *
     * <p>策略：先以启发式分析作为基线（保证可量化的字段稳定），再用 LLM 输出的结构化 JSON
     * 覆盖/补充主观与视觉类字段（观点鲜明度、情感倾向、幽默感、视觉风格等）。
     * 包含降级：ChatModel 不可用、调用异常或 JSON 解析失败时，回退到 {@link #analyzeStyle}。
     *
     * @param content   内容正文
     * @param chatModel 可用的 ChatModel；为 null 时直接降级到启发式
     * @return 风格画像
     */
    public StyleProfile analyzeStyleWithLLM(String content, ChatModel chatModel) {
        StyleProfile baseline = analyzeStyle(content);
        if (chatModel == null) {
            log.debug("[Style] ChatModel 为空，LLM 分析降级为启发式");
            return baseline;
        }
        if (content == null || content.isBlank()) {
            return baseline;
        }
        try {
            String llmJson = invokeLlmStyleAnalysis(content, chatModel);
            if (llmJson == null || llmJson.isBlank()) {
                log.debug("[Style] LLM 返回空内容，降级为启发式");
                return baseline;
            }
            JsonNode node = objectMapper.readTree(extractJsonBlock(llmJson));
            return overlayLlmResult(baseline, node);
        } catch (Exception e) {
            log.warn("[Style] LLM 风格分析失败，降级为启发式: {}", e.getMessage());
            return baseline;
        }
    }

    /**
     * 对多篇内容聚合生成完整风格画像。
     *
     * <p>按 {@link StyleProfileProperties#getMaxContents()} 截断后逐篇分析，
     * 再通过 {@link StyleProfile#aggregate(List)} 按样本数加权归并。
     * 分析通路由 {@link StyleProfileProperties#isLlmAnalysisActive()} 决定。
     *
     * @param contents 内容正文列表
     * @return 聚合后的完整风格画像（accountId 为空）
     */
    public StyleProfile buildProfile(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return StyleProfile.empty();
        }
        int limit = Math.max(1, properties.getMaxContents());
        List<StyleProfile> perContent = contents.stream()
                .filter(c -> c != null && !c.isBlank())
                .limit(limit)
                .map(this::analyzeByMode)
                .toList();
        if (perContent.isEmpty()) {
            return StyleProfile.empty();
        }
        return StyleProfile.aggregate(perContent);
    }

    /**
     * 比较两个风格画像的异同。
     *
     * @param profile1 第一个画像
     * @param profile2 第二个画像
     * @return 风格比较结果（含综合相似度、各维度相似度与差异说明）
     */
    public StyleComparison compareStyles(StyleProfile profile1, StyleProfile profile2) {
        if (profile1 == null || profile2 == null) {
            return new StyleComparison(0.0, 0.0, 0.0, 0.0, 0.0, List.of("参与比较的画像为空"));
        }
        double overall = profile1.similarityScore(profile2);
        double lang = profile1.languageStyle().similarity(profile2.languageStyle());
        double struct = profile1.structureStyle().similarity(profile2.structureStyle());
        double content = profile1.contentStyle().similarity(profile2.contentStyle());
        double visual = profile1.visualStyle().similarity(profile2.visualStyle());
        List<String> diffs = buildDiffNotes(profile1, profile2, lang, struct, content, visual);
        return new StyleComparison(overall, lang, struct, content, visual, diffs);
    }

    /**
     * 提取风格签名（用于向量化入库与相似检索）。
     *
     * <p>将内容分析后的风格特征浓缩为一段语义化中文文本，作为 embedding 的查询文本。
     *
     * @param content 内容正文
     * @return 风格签名文本
     */
    public String extractStyleSignature(String content) {
        return extractStyleSignature(analyzeStyle(content));
    }

    /**
     * 提取风格签名（重载：基于已有画像）。
     *
     * @param profile 风格画像
     * @return 风格签名文本
     */
    public String extractStyleSignature(StyleProfile profile) {
        if (profile == null) {
            return "风格签名：未知";
        }
        LanguageStyle ls = profile.languageStyle();
        StructureStyle ss = profile.structureStyle();
        ContentStyle cs = profile.contentStyle();
        VisualStyle vs = profile.visualStyle();
        return new StringBuilder()
                .append("风格签名：")
                .append("语言风格-长句占比").append(round(ls.longSentenceRatio()))
                .append(",短句占比").append(round(ls.shortSentenceRatio()))
                .append(",问句占比").append(round(ls.questionSentenceRatio()))
                .append(",专业术语密度").append(round(ls.terminologyDensity()))
                .append(",口语化").append(round(ls.colloquialism()))
                .append(",平均句长").append((int) ls.averageSentenceLength())
                .append(";结构-").append(ss.openingMode().label()).append("开头")
                .append(",").append(ss.paragraphStructure().label()).append("结构")
                .append(",").append(ss.endingMode().label()).append("结尾")
                .append(",").append(ss.titleStyle().label()).append("标题")
                .append(";内容-观点鲜明度").append(round(cs.opinionClarity()))
                .append(",数据引用").append(round(cs.dataCitationFrequency()))
                .append(",案例").append(round(cs.caseUsageFrequency()))
                .append(",个人经历").append(round(cs.personalExperienceRatio()))
                .append(",情感").append(cs.emotionalTendency().label())
                .append(",幽默").append(round(cs.humorLevel()))
                .append(";视觉-").append(vs.coverTone().label())
                .append(",").append(vs.illustrationStyle().label()).append("配图")
                .append(",").append(vs.layoutDensity().label()).append("排版")
                .toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  启发式分析：各维度
    // ════════════════════════════════════════════════════════════════

    /** 语言风格分析。 */
    private LanguageStyle analyzeLanguageStyle(String text, List<String> sentences,
                                               List<String> paragraphs, int totalChars) {
        int sentenceCount = Math.max(1, sentences.size());
        int longCount = 0;
        int shortCount = 0;
        int totalSentenceChars = 0;
        for (String s : sentences) {
            int len = countChars(s);
            totalSentenceChars += len;
            if (len > 30) longCount++;
            if (len < 15 && len > 0) shortCount++;
        }
        // 问句数按全文问号计数：分句已消耗句末问号，故在原文上统计
        int questionCount = countPattern(text, QUESTION_MARK);
        double longRatio = (double) longCount / sentenceCount;
        double shortRatio = (double) shortCount / sentenceCount;
        double questionRatio = (double) questionCount / sentenceCount;
        double avgSentenceLen = (double) totalSentenceChars / sentenceCount;
        double avgParagraphLen = paragraphs.isEmpty() ? totalChars : (double) totalChars / paragraphs.size();

        double terminology = computeTerminologyDensity(text, totalChars);
        double colloquialism = computeColloquialism(text, sentences, totalChars);
        double emojiFreq = computeEmojiFrequency(text, totalChars);

        return new LanguageStyle(
                longRatio, shortRatio, questionRatio,
                terminology, colloquialism, emojiFreq,
                avgSentenceLen, avgParagraphLen
        );
    }

    /** 结构风格分析。 */
    private StructureStyle analyzeStructureStyle(String title, String firstPara,
                                                 String lastPara, List<String> paragraphs) {
        OpeningMode opening = detectOpeningMode(firstPara);
        ParagraphStructure structure = detectParagraphStructure(paragraphs);
        EndingMode ending = detectEndingMode(lastPara);
        TitleStyle titleStyle = detectTitleStyle(title);
        return new StructureStyle(opening, structure, ending, titleStyle);
    }

    /** 内容特征分析。 */
    private ContentStyle analyzeContentStyle(String text, List<String> sentences, int totalChars) {
        int sentenceCount = Math.max(1, sentences.size());
        double opinionClarity = clamp01(countOccurrences(text, OPINION_MARKERS) * 0.15
                + countPattern(text, EXCLAIM_MARK) * 0.05);
        double dataFreq = clamp01((countPattern(text, NUMBER_PERCENT) * 0.1
                + countOccurrences(text, DATA_MARKERS) * 0.08) * (200.0 / Math.max(50, totalChars)));
        double caseFreq = clamp01(countOccurrences(text, CASE_MARKERS) * 0.2);
        double personalRatio = clamp01((countOccurrences(text, FIRST_PERSON) * 0.12)
                / Math.max(1.0, sentenceCount / 5.0));
        EmotionalTendency tendency = detectEmotionalTendency(text);
        double humor = clamp01(countOccurrences(text, HUMOR_MARKERS) * 0.2
                + countPattern(text, EMOJI) * 0.03);
        return new ContentStyle(opinionClarity, dataFreq, caseFreq, personalRatio, tendency, humor);
    }

    /**
     * 视觉风格分析（启发式仅能粗略推断，建议由 LLM 或图片分析增强）。
     */
    private VisualStyle analyzeVisualStyle(String title, int totalChars) {
        String layout;
        int titleLen = title == null ? 0 : countChars(title);
        if (titleLen > 0 && titleLen <= 12) {
            layout = "短标题居中加大";
        } else if (titleLen > 20) {
            layout = "长标题左对齐";
        } else {
            layout = "标题适中居左";
        }
        // 启发式默认：中性色调、极简配图、适中排版；LLM 覆盖
        return new VisualStyle(layout, CoverTone.NEUTRAL, IllustrationStyle.MINIMAL, LayoutDensity.MODERATE);
    }

    // ════════════════════════════════════════════════════════════════
    //  启发式分析：检测子方法
    // ════════════════════════════════════════════════════════════════

    /** 开头模式检测：提问 > 故事 > 数据 > 观点 > 资讯。 */
    private OpeningMode detectOpeningMode(String firstPara) {
        if (firstPara == null || firstPara.isBlank()) {
            return OpeningMode.NEWS;
        }
        if (QUESTION_MARK.matcher(firstPara).find()) {
            return OpeningMode.QUESTION;
        }
        boolean hasTime = containsAny(firstPara, STORY_TIME);
        boolean hasPerson = containsAny(firstPara, STORY_PERSON);
        if (hasTime && hasPerson) {
            return OpeningMode.STORY;
        }
        if (countPattern(firstPara, NUMBER_PERCENT) > 0 || containsAny(firstPara, DATA_MARKERS)) {
            return OpeningMode.DATA;
        }
        if (containsAny(firstPara, OPINION_MARKERS)) {
            return OpeningMode.OPINION;
        }
        return OpeningMode.NEWS;
    }

    /** 段落结构检测：清单 > 递进 > 总分总 > 并列。 */
    private ParagraphStructure detectParagraphStructure(List<String> paragraphs) {
        if (paragraphs == null || paragraphs.size() < 2) {
            return ParagraphStructure.PROGRESSIVE;
        }
        int listMarkers = 0;
        int progressiveMarkers = 0;
        for (String p : paragraphs) {
            if (p.matches("(?s)^\\s*(\\d+[.、)]|[-*•])\\s+.*")) {
                listMarkers++;
            }
            progressiveMarkers += countOccurrences(p, PROGRESSIVE_MARKERS);
        }
        if (listMarkers >= 2) {
            return ParagraphStructure.LIST;
        }
        if (progressiveMarkers >= 2) {
            return ParagraphStructure.PROGRESSIVE;
        }
        String first = paragraphs.get(0);
        String last = paragraphs.get(paragraphs.size() - 1);
        if (containsAny(first, SUMMARY_MARKERS) || containsAny(last, SUMMARY_MARKERS)
                || containsAny(last, CTA_MARKERS)) {
            return ParagraphStructure.TOTAL_DIVIDED_TOTAL;
        }
        return ParagraphStructure.PARALLEL;
    }

    /** 结尾模式检测：号召 > 提问 > 总结 > 开放。 */
    private EndingMode detectEndingMode(String lastPara) {
        if (lastPara == null || lastPara.isBlank()) {
            return EndingMode.OPEN_ENDED;
        }
        if (containsAny(lastPara, CTA_MARKERS)) {
            return EndingMode.CALL_TO_ACTION;
        }
        if (QUESTION_MARK.matcher(lastPara).find()) {
            return EndingMode.QUESTION;
        }
        if (containsAny(lastPara, SUMMARY_MARKERS)) {
            return EndingMode.SUMMARY;
        }
        return EndingMode.OPEN_ENDED;
    }

    /** 标题风格检测：数字 > 教程 > 疑问 > 情感 > 资讯。 */
    private TitleStyle detectTitleStyle(String title) {
        if (title == null || title.isBlank()) {
            return TitleStyle.NEWS;
        }
        if (NUMBER_TITLE.matcher(title).find()) {
            return TitleStyle.NUMBER;
        }
        if (title.contains("如何") || title.contains("怎么") || title.contains("怎样")) {
            return TitleStyle.HOW_TO;
        }
        if (QUESTION_MARK.matcher(title).find()) {
            return TitleStyle.QUESTION;
        }
        if (EXCLAIM_MARK.matcher(title).find() || containsAny(title, EMOTIONAL_TITLE_MARKERS)) {
            return TitleStyle.EMOTIONAL;
        }
        return TitleStyle.NEWS;
    }

    /** 情感倾向检测：正面词 vs 负面词。 */
    private EmotionalTendency detectEmotionalTendency(String text) {
        int pos = countOccurrences(text, POSITIVE_WORDS);
        int neg = countOccurrences(text, NEGATIVE_WORDS);
        if (pos > neg) {
            return EmotionalTendency.POSITIVE;
        }
        if (neg > pos) {
            return EmotionalTendency.CRITICAL;
        }
        return EmotionalTendency.NEUTRAL;
    }

    /**
     * 专业术语密度：优先按领域词典匹配；无词典时用生僻字比例估算。
     */
    private double computeTerminologyDensity(String text, int totalChars) {
        if (totalChars <= 0) {
            return 0.0;
        }
        List<String> terms = properties.getDomainTerms();
        if (terms != null && !terms.isEmpty()) {
            int matches = 0;
            for (String term : terms) {
                if (term != null && !term.isBlank() && text.contains(term)) {
                    matches++;
                }
            }
            // 每 200 字命中 1 个术语约对应 0.1 密度
            double per200 = matches * 200.0 / totalChars;
            return clamp01(per200 * 0.2);
        }
        // 无词典：用大写英文字母/长英文词密度估算专业度
        long asciiLetterRuns = Pattern.compile("[A-Za-z]{2,}").matcher(text).results().count();
        double density = (double) asciiLetterRuns / (totalChars / 50.0);
        return clamp01(density * 0.15);
    }

    /**
     * 口语化程度：语气词密度 *0.4 + 感叹号比例 *0.3 + 第一人称密度 *0.3。
     */
    private double computeColloquialism(String text, List<String> sentences, int totalChars) {
        if (totalChars <= 0) {
            return 0.0;
        }
        double particleDensity = clamp01(countOccurrences(text, PARTICLES) * 50.0 / totalChars);
        double exclaimRatio = sentences.isEmpty() ? 0
                : (double) countPattern(text, EXCLAIM_MARK) / sentences.size();
        double firstPersonDensity = clamp01(countOccurrences(text, FIRST_PERSON) * 50.0 / totalChars);
        return clamp01(particleDensity * 0.4 + exclaimRatio * 0.3 + firstPersonDensity * 0.3);
    }

    /** emoji 使用频率（每 100 字）。 */
    private double computeEmojiFrequency(String text, int totalChars) {
        if (totalChars <= 0) {
            return 0.0;
        }
        int count = countPattern(text, EMOJI);
        return count * 100.0 / totalChars;
    }

    // ════════════════════════════════════════════════════════════════
    //  LLM 分析
    // ════════════════════════════════════════════════════════════════

    /** 按配置模式选择分析通路（供 buildProfile 使用）。 */
    private StyleProfile analyzeByMode(String content) {
        if (properties.isLlmAnalysisActive()) {
            ChatModel chatModel = chatModelProvider.getIfAvailable();
            if (chatModel != null) {
                return analyzeStyleWithLLM(content, chatModel);
            }
            log.debug("[Style] LLM 模式开启但 ChatModel 不可用，降级为启发式");
        }
        return analyzeStyle(content);
    }

    /** 调用 LLM 做风格标注，返回原始文本（含 JSON）。 */
    private String invokeLlmStyleAnalysis(String content, ChatModel chatModel) {
        String systemPrompt = """
                你是一个专业的内容风格分析师。请对给定的内容做风格标注，仅输出严格合法的 JSON（不要包含任何解释文字、不要使用 markdown 代码块）。
                JSON 结构如下，所有数值字段取值范围 0~1，枚举字段必须使用给定取值：
                {
                  "opinionClarity": 0.0~1.0,
                  "emotionalTendency": "POSITIVE" | "NEUTRAL" | "CRITICAL",
                  "humorLevel": 0.0~1.0,
                  "coverTone": "WARM" | "COOL" | "NEUTRAL" | "BRIGHT" | "MUTED",
                  "illustrationStyle": "MINIMAL" | "ILLUSTRATION" | "PHOTO" | "INFOGRAPHIC",
                  "layoutDensity": "SPARSE" | "MODERATE" | "DENSE",
                  "titleLayoutPreference": "对该账号标题排版的简短描述"
                }
                """;
        String userPrompt = "请分析以下内容的风格特征并输出 JSON：\n\n" + truncate(content, 4000);
        var response = chatModel.chat(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt));
        return response.aiMessage() == null ? null : response.aiMessage().text();
    }

    /** 用 LLM 返回的 JSON 覆盖基线画像中的主观/视觉字段。 */
    private StyleProfile overlayLlmResult(StyleProfile baseline, JsonNode node) {
        ContentStyle base = baseline.contentStyle();
        ContentStyle contentStyle = new ContentStyle(
                node.has("opinionClarity") ? node.get("opinionClarity").asDouble(base.opinionClarity()) : base.opinionClarity(),
                base.dataCitationFrequency(),
                base.caseUsageFrequency(),
                base.personalExperienceRatio(),
                parseEnum(node, "emotionalTendency", EmotionalTendency.class, base.emotionalTendency()),
                node.has("humorLevel") ? node.get("humorLevel").asDouble(base.humorLevel()) : base.humorLevel()
        );

        VisualStyle vsBase = baseline.visualStyle();
        VisualStyle visualStyle = new VisualStyle(
                node.has("titleLayoutPreference") && !node.get("titleLayoutPreference").asText().isBlank()
                        ? node.get("titleLayoutPreference").asText() : vsBase.titleLayoutPreference(),
                parseEnum(node, "coverTone", CoverTone.class, vsBase.coverTone()),
                parseEnum(node, "illustrationStyle", IllustrationStyle.class, vsBase.illustrationStyle()),
                parseEnum(node, "layoutDensity", LayoutDensity.class, vsBase.layoutDensity())
        );

        return new StyleProfile(
                baseline.accountId(),
                baseline.languageStyle(),
                baseline.structureStyle(),
                contentStyle,
                visualStyle,
                baseline.sampleCount(),
                baseline.createdAt(),
                java.time.Instant.now()
        );
    }

    /** 从可能含 markdown 代码块的文本中提取 JSON 片段。 */
    private String extractJsonBlock(String raw) {
        if (raw == null) {
            return "{}";
        }
        Matcher m = Pattern.compile("\\{[\\s\\S]*\\}").matcher(raw);
        return m.find() ? m.group() : raw.trim();
    }

    /** 安全解析枚举字段，失败时返回默认值。 */
    private <E extends Enum<E>> E parseEnum(JsonNode node, String field, Class<E> enumType, E defaultValue) {
        try {
            if (node.has(field) && !node.get(field).isNull()) {
                return Enum.valueOf(enumType, node.get(field).asText().toUpperCase());
            }
        } catch (Exception ignored) {
            // 解析失败保留默认值
        }
        return defaultValue;
    }

    // ════════════════════════════════════════════════════════════════
    //  比较结果构建
    // ════════════════════════════════════════════════════════════════

    /** 生成两画像的差异说明列表。 */
    private List<String> buildDiffNotes(StyleProfile p1, StyleProfile p2,
                                        double lang, double struct, double content, double visual) {
        List<String> diffs = new ArrayList<>();
        diffs.add(String.format("综合相似度：%.2f", p1.similarityScore(p2)));
        diffs.add(String.format("语言风格相似度：%.2f（长句占比 %.2f vs %.2f，口语化 %.2f vs %.2f）",
                lang, p1.languageStyle().longSentenceRatio(), p2.languageStyle().longSentenceRatio(),
                p1.languageStyle().colloquialism(), p2.languageStyle().colloquialism()));
        diffs.add(String.format("结构风格相似度：%.2f（开头 %s vs %s，结尾 %s vs %s）",
                struct, p1.structureStyle().openingMode().label(), p2.structureStyle().openingMode().label(),
                p1.structureStyle().endingMode().label(), p2.structureStyle().endingMode().label()));
        diffs.add(String.format("内容特征相似度：%.2f（情感 %s vs %s，观点鲜明度 %.2f vs %.2f）",
                content, p1.contentStyle().emotionalTendency().label(), p2.contentStyle().emotionalTendency().label(),
                p1.contentStyle().opinionClarity(), p2.contentStyle().opinionClarity()));
        diffs.add(String.format("视觉风格相似度：%.2f（色调 %s vs %s，配图 %s vs %s）",
                visual, p1.visualStyle().coverTone().label(), p2.visualStyle().coverTone().label(),
                p1.visualStyle().illustrationStyle().label(), p2.visualStyle().illustrationStyle().label()));
        return diffs;
    }

    /**
     * 风格比较结果。
     *
     * @param overallScore  综合相似度
     * @param languageScore 语言风格相似度
     * @param structureScore 结构风格相似度
     * @param contentScore  内容特征相似度
     * @param visualScore   视觉风格相似度
     * @param differences   差异说明列表
     */
    public record StyleComparison(
            double overallScore,
            double languageScore,
            double structureScore,
            double contentScore,
            double visualScore,
            List<String> differences
    ) {}

    // ════════════════════════════════════════════════════════════════
    //  文本处理工具方法
    // ════════════════════════════════════════════════════════════════

    /** 按空行/换行分段，过滤空段。 */
    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String p : text.split("\\n\\s*\\n|\\r\\n\\r\\n")) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        if (paragraphs.isEmpty()) {
            for (String line : text.split("\\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    paragraphs.add(trimmed);
                }
            }
        }
        return paragraphs;
    }

    /** 按句末标点分句。 */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String s : SENTENCE_SPLIT.split(text)) {
            String trimmed = s.strip();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    /** 提取标题：首个非空短行（≤40 字且独立成段）。 */
    private String extractTitle(List<String> paragraphs) {
        if (paragraphs.isEmpty()) {
            return "";
        }
        String first = paragraphs.get(0);
        return countChars(first) <= 40 ? first : "";
    }

    /** 统计有效字符数（去除空白）。 */
    private int countChars(String text) {
        if (text == null) {
            return 0;
        }
        return (int) text.chars().filter(c -> !Character.isWhitespace(c)).count();
    }

    /** 统计集合中任意词在文本中的出现次数。 */
    private int countOccurrences(String text, Set<String> words) {
        if (text == null || words == null) {
            return 0;
        }
        int count = 0;
        for (String w : words) {
            if (w == null || w.isEmpty()) {
                continue;
            }
            int idx = 0;
            while ((idx = text.indexOf(w, idx)) >= 0) {
                count++;
                idx += w.length();
            }
        }
        return count;
    }

    /** 文本是否包含集合中任意词。 */
    private boolean containsAny(String text, Set<String> words) {
        return countOccurrences(text, words) > 0;
    }

    /** 统计正则匹配次数。 */
    private int countPattern(String text, Pattern pattern) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            count++;
        }
        return count;
    }

    /** 截断文本到指定长度。 */
    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    /** 保留两位小数。 */
    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 限制到 [0,1]。 */
    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
