package com.contentops.common.profile.style;

import java.time.Instant;
import java.util.List;

/**
 * 风格画像数据结构（P0 改造：从静态 tone 字段升级为基于作品分析的风格特征提取）。
 *
 * <p>本类是整个风格画像系统的核心数据载体，采用 Java {@code record} 实现，不可变且线程安全。
 * 风格画像由四个维度组成，每个维度都是一个嵌套的 {@code record}，并共同实现
 * {@link StyleDimension} 密封接口（sealed interface），便于在 {@link StyleEnricher} 中通过
 * 模式匹配 switch 统一渲染。
 *
 * <h3>四维风格特征</h3>
 * <ul>
 *   <li>{@link LanguageStyle} —— 语言风格：句式分布、用词复杂度、口语化程度、emoji 频率、平均句长、段落平均长度</li>
 *   <li>{@link StructureStyle} —— 结构风格：开头模式、段落结构、结尾模式、标题风格</li>
 *   <li>{@link ContentStyle} —— 内容特征：观点鲜明度、数据引用频率、案例使用频率、个人经历占比、情感倾向、幽默感程度</li>
 *   <li>{@link VisualStyle} —— 视觉风格：标题排版偏好、封面色调倾向、配图风格、排版密度</li>
 * </ul>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>{@link #merge(StyleProfile)} —— 按样本数加权聚合多篇作品的风格特征，用于增量更新与批量建模</li>
 *   <li>{@link #similarityScore(StyleProfile)} —— 计算两个风格画像的综合相似度（0.0~1.0），用于相似账号检索</li>
 *   <li>{@link #aggregate(List)} —— 将多篇内容的画像归并为一个完整画像</li>
 * </ul>
 *
 * <p>所有数值型特征均归一化到 {@code [0,1]} 区间（emoji 频率、平均句长等量纲字段除外），
 * 便于跨维度加权与相似度计算。
 *
 * @see StyleDimension
 * @see LanguageStyle
 * @see StructureStyle
 * @see ContentStyle
 * @see VisualStyle
 */
public record StyleProfile(
        /** 账号 ID（单篇内容分析中间结果可为 null） */
        String accountId,
        /** 语言风格特征 */
        LanguageStyle languageStyle,
        /** 结构风格特征 */
        StructureStyle structureStyle,
        /** 内容特征 */
        ContentStyle contentStyle,
        /** 视觉风格特征 */
        VisualStyle visualStyle,
        /** 聚合样本数（参与聚合的作品篇数，用于加权合并） */
        int sampleCount,
        /** 画像创建时间 */
        Instant createdAt,
        /** 画像最后更新时间 */
        Instant updatedAt
) {

    /**
     * 紧凑构造器：对入参做空值兜底与归一化，保证画像对象始终处于合法状态。
     */
    public StyleProfile {
        if (languageStyle == null) languageStyle = LanguageStyle.empty();
        if (structureStyle == null) structureStyle = StructureStyle.empty();
        if (contentStyle == null) contentStyle = ContentStyle.empty();
        if (visualStyle == null) visualStyle = VisualStyle.empty();
        if (sampleCount < 1) sampleCount = 1;
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    /**
     * 密封接口：统一四个风格维度，支持模式匹配渲染与加权相似度计算。
     *
     * <p>使用 {@code sealed} + {@code permits} 限定实现集合，使编译器能在模式匹配 switch 中
     * 进行穷尽性检查，是 Java 21 特性的典型应用场景。
     */
    public sealed interface StyleDimension permits LanguageStyle, StructureStyle, ContentStyle, VisualStyle {
        /**
         * 该维度在综合相似度计算中的权重（0~1，无需归一化，由调用方按总和归一）。
         *
         * @return 维度权重
         */
        default double weight() {
            return 0.25;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  语言风格
    // ════════════════════════════════════════════════════════════════

    /**
     * 语言风格特征：刻画作者在句式、用词、口语化等方面的语言习惯。
     *
     * @param longSentenceRatio       长句占比（>30 字的句子占比，0~1）
     * @param shortSentenceRatio      短句占比（<15 字的句子占比，0~1）
     * @param questionSentenceRatio   问句占比（以问号结尾的句子占比，0~1）
     * @param terminologyDensity      专业术语密度（0~1，越高越专业）
     * @param colloquialism           口语化程度（0~1，越高越口语化）
     * @param emojiFrequency          emoji 使用频率（每 100 字 emoji 数，≥0）
     * @param averageSentenceLength   平均句长（字符数，≥0）
     * @param averageParagraphLength  段落平均长度（字符数，≥0）
     */
    public record LanguageStyle(
            double longSentenceRatio,
            double shortSentenceRatio,
            double questionSentenceRatio,
            double terminologyDensity,
            double colloquialism,
            double emojiFrequency,
            double averageSentenceLength,
            double averageParagraphLength
    ) implements StyleDimension {

        /** 紧凑构造器：归一化数值字段。 */
        public LanguageStyle {
            longSentenceRatio = clamp01(longSentenceRatio);
            shortSentenceRatio = clamp01(shortSentenceRatio);
            questionSentenceRatio = clamp01(questionSentenceRatio);
            terminologyDensity = clamp01(terminologyDensity);
            colloquialism = clamp01(colloquialism);
            emojiFrequency = Math.max(0, emojiFrequency);
            averageSentenceLength = Math.max(0, averageSentenceLength);
            averageParagraphLength = Math.max(0, averageParagraphLength);
        }

        /** 创建全零的空语言风格（用于兜底）。 */
        public static LanguageStyle empty() {
            return new LanguageStyle(0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** 语言风格在综合相似度中的权重。 */
        @Override
        public double weight() {
            return 0.30;
        }

        /**
         * 按权重合并两个语言风格（加权平均）。
         *
         * @param other 另一篇作品的语言风格
         * @param w1    当前画像的权重
         * @param w2    另一篇的权重
         * @return 合并后的语言风格
         */
        public LanguageStyle merge(LanguageStyle other, double w1, double w2) {
            if (other == null) {
                return this;
            }
            return new LanguageStyle(
                    w1 * longSentenceRatio + w2 * other.longSentenceRatio,
                    w1 * shortSentenceRatio + w2 * other.shortSentenceRatio,
                    w1 * questionSentenceRatio + w2 * other.questionSentenceRatio,
                    w1 * terminologyDensity + w2 * other.terminologyDensity,
                    w1 * colloquialism + w2 * other.colloquialism,
                    w1 * emojiFrequency + w2 * other.emojiFrequency,
                    w1 * averageSentenceLength + w2 * other.averageSentenceLength,
                    w1 * averageParagraphLength + w2 * other.averageParagraphLength
            );
        }

        /**
         * 计算两个语言风格的相似度（0~1）：8 个字段的归一化差异均值。
         */
        public double similarity(LanguageStyle other) {
            if (other == null) {
                return 0.0;
            }
            double sum = 0;
            sum += ratioSim(longSentenceRatio, other.longSentenceRatio);
            sum += ratioSim(shortSentenceRatio, other.shortSentenceRatio);
            sum += ratioSim(questionSentenceRatio, other.questionSentenceRatio);
            sum += ratioSim(terminologyDensity, other.terminologyDensity);
            sum += ratioSim(colloquialism, other.colloquialism);
            sum += rangeSim(emojiFrequency, other.emojiFrequency, 5.0);
            sum += rangeSim(averageSentenceLength, other.averageSentenceLength, 30.0);
            sum += rangeSim(averageParagraphLength, other.averageParagraphLength, 300.0);
            return sum / 8.0;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  结构风格
    // ════════════════════════════════════════════════════════════════

    /**
     * 开头模式枚举。
     */
    public enum OpeningMode {
        /** 故事型开头（时间/人物切入） */
        STORY("故事型"),
        /** 观点型开头（直接抛出观点） */
        OPINION("观点型"),
        /** 数据型开头（数字/报告切入） */
        DATA("数据型"),
        /** 提问型开头（疑问句切入） */
        QUESTION("提问型"),
        /** 资讯型开头（新闻陈述） */
        NEWS("资讯型");

        private final String label;

        OpeningMode(String label) {
            this.label = label;
        }

        /** 中文标签，用于生成可读风格指南。 */
        public String label() {
            return label;
        }
    }

    /**
     * 段落结构枚举。
     */
    public enum ParagraphStructure {
        /** 总分总结构 */
        TOTAL_DIVIDED_TOTAL("总分总"),
        /** 并列结构 */
        PARALLEL("并列"),
        /** 递进结构 */
        PROGRESSIVE("递进"),
        /** 清单/列表结构 */
        LIST("清单");

        private final String label;

        ParagraphStructure(String label) {
            this.label = label;
        }

        /** 中文标签。 */
        public String label() {
            return label;
        }
    }

    /**
     * 结尾模式枚举。
     */
    public enum EndingMode {
        /** 号召行动式结尾 */
        CALL_TO_ACTION("号召式"),
        /** 总结式结尾 */
        SUMMARY("总结式"),
        /** 提问式结尾 */
        QUESTION("提问式"),
        /** 开放式结尾 */
        OPEN_ENDED("开放式");

        private final String label;

        EndingMode(String label) {
            this.label = label;
        }

        /** 中文标签。 */
        public String label() {
            return label;
        }
    }

    /**
     * 标题风格枚举。
     */
    public enum TitleStyle {
        /** 数字型标题（如「3 个方法」） */
        NUMBER("数字型"),
        /** 教程型标题（如「如何…」） */
        HOW_TO("教程型"),
        /** 疑问型标题 */
        QUESTION("疑问型"),
        /** 情感型标题 */
        EMOTIONAL("情感型"),
        /** 资讯型标题 */
        NEWS("资讯型");

        private final String label;

        TitleStyle(String label) {
            this.label = label;
        }

        /** 中文标签。 */
        public String label() {
            return label;
        }
    }

    /**
     * 结构风格特征：刻画作者在文章骨架、开头结尾、标题套路上的偏好。
     *
     * @param openingMode         开头模式
     * @param paragraphStructure  段落结构
     * @param endingMode          结尾模式
     * @param titleStyle          标题风格
     */
    public record StructureStyle(
            OpeningMode openingMode,
            ParagraphStructure paragraphStructure,
            EndingMode endingMode,
            TitleStyle titleStyle
    ) implements StyleDimension {

        /** 紧凑构造器：枚举字段空值兜底。 */
        public StructureStyle {
            if (openingMode == null) openingMode = OpeningMode.NEWS;
            if (paragraphStructure == null) paragraphStructure = ParagraphStructure.PROGRESSIVE;
            if (endingMode == null) endingMode = EndingMode.SUMMARY;
            if (titleStyle == null) titleStyle = TitleStyle.NEWS;
        }

        /** 创建默认结构风格。 */
        public static StructureStyle empty() {
            return new StructureStyle(null, null, null, null);
        }

        /** 结构风格在综合相似度中的权重。 */
        @Override
        public double weight() {
            return 0.25;
        }

        /**
         * 按权重合并两个结构风格：分类字段采用加权投票（取权重较大一方的取值）。
         */
        public StructureStyle merge(StructureStyle other, double w1, double w2) {
            if (other == null) {
                return this;
            }
            return new StructureStyle(
                    w1 >= w2 ? openingMode : other.openingMode,
                    w1 >= w2 ? paragraphStructure : other.paragraphStructure,
                    w1 >= w2 ? endingMode : other.endingMode,
                    w1 >= w2 ? titleStyle : other.titleStyle
            );
        }

        /**
         * 计算两个结构风格的相似度（0~1）：4 个分类字段完全匹配计 1，否则 0，取均值。
         */
        public double similarity(StructureStyle other) {
            if (other == null) {
                return 0.0;
            }
            double sum = 0;
            sum += openingMode == other.openingMode ? 1 : 0;
            sum += paragraphStructure == other.paragraphStructure ? 1 : 0;
            sum += endingMode == other.endingMode ? 1 : 0;
            sum += titleStyle == other.titleStyle ? 1 : 0;
            return sum / 4.0;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内容特征
    // ════════════════════════════════════════════════════════════════

    /**
     * 情感倾向枚举。
     */
    public enum EmotionalTendency {
        /** 积极 */
        POSITIVE("积极"),
        /** 中性 */
        NEUTRAL("中性"),
        /** 批判/消极 */
        CRITICAL("批判");

        private final String label;

        EmotionalTendency(String label) {
            this.label = label;
        }

        /** 中文标签。 */
        public String label() {
            return label;
        }
    }

    /**
     * 内容特征：刻画作者在观点表达、数据引用、案例使用、情感倾向等方面的内容偏好。
     *
     * @param opinionClarity           观点鲜明度（0~1）
     * @param dataCitationFrequency    数据引用频率（0~1）
     * @param caseUsageFrequency       案例使用频率（0~1）
     * @param personalExperienceRatio  个人经历占比（0~1）
     * @param emotionalTendency        情感倾向
     * @param humorLevel               幽默感程度（0~1）
     */
    public record ContentStyle(
            double opinionClarity,
            double dataCitationFrequency,
            double caseUsageFrequency,
            double personalExperienceRatio,
            EmotionalTendency emotionalTendency,
            double humorLevel
    ) implements StyleDimension {

        /** 紧凑构造器：归一化与枚举兜底。 */
        public ContentStyle {
            opinionClarity = clamp01(opinionClarity);
            dataCitationFrequency = clamp01(dataCitationFrequency);
            caseUsageFrequency = clamp01(caseUsageFrequency);
            personalExperienceRatio = clamp01(personalExperienceRatio);
            humorLevel = clamp01(humorLevel);
            if (emotionalTendency == null) emotionalTendency = EmotionalTendency.NEUTRAL;
        }

        /** 创建默认内容特征。 */
        public static ContentStyle empty() {
            return new ContentStyle(0, 0, 0, 0, null, 0);
        }

        /** 内容特征在综合相似度中的权重。 */
        @Override
        public double weight() {
            return 0.25;
        }

        /**
         * 按权重合并两个内容特征：数值字段加权平均，情感倾向加权投票。
         */
        public ContentStyle merge(ContentStyle other, double w1, double w2) {
            if (other == null) {
                return this;
            }
            return new ContentStyle(
                    w1 * opinionClarity + w2 * other.opinionClarity,
                    w1 * dataCitationFrequency + w2 * other.dataCitationFrequency,
                    w1 * caseUsageFrequency + w2 * other.caseUsageFrequency,
                    w1 * personalExperienceRatio + w2 * other.personalExperienceRatio,
                    w1 >= w2 ? emotionalTendency : other.emotionalTendency,
                    w1 * humorLevel + w2 * other.humorLevel
            );
        }

        /**
         * 计算两个内容特征的相似度（0~1）：5 个数值字段差异均值 + 情感倾向匹配，取均值。
         */
        public double similarity(ContentStyle other) {
            if (other == null) {
                return 0.0;
            }
            double sum = 0;
            sum += ratioSim(opinionClarity, other.opinionClarity);
            sum += ratioSim(dataCitationFrequency, other.dataCitationFrequency);
            sum += ratioSim(caseUsageFrequency, other.caseUsageFrequency);
            sum += ratioSim(personalExperienceRatio, other.personalExperienceRatio);
            sum += ratioSim(humorLevel, other.humorLevel);
            sum += emotionalTendency == other.emotionalTendency ? 1 : 0;
            return sum / 6.0;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  视觉风格
    // ════════════════════════════════════════════════════════════════

    /**
     * 封面色调倾向枚举。
     */
    public enum CoverTone {
        /** 暖色调 */
        WARM("暖色调"),
        /** 冷色调 */
        COOL("冷色调"),
        /** 中性色调 */
        NEUTRAL("中性色调"),
        /** 明亮色调 */
        BRIGHT("明亮色调"),
        /** 低饱和/暗沉色调 */
        MUTED("低饱和色调");

        private final String label;

        CoverTone(String label) {
            this.label = label;
        }

        /** 中文标签。 */
        public String label() {
            return label;
        }
    }

    /**
     * 配图风格枚举。
     */
    public enum IllustrationStyle {
        /** 极简风格 */
        MINIMAL("极简"),
        /** 插画风格 */
        ILLUSTRATION("插画"),
        /** 实拍照片 */
        PHOTO("实拍"),
        /** 信息图 */
        INFOGRAPHIC("信息图");

        private final String label;

        IllustrationStyle(String label) {
            this.label = label;
        }

        /** 中文标签。 */
        public String label() {
            return label;
        }
    }

    /**
     * 排版密度枚举。
     */
    public enum LayoutDensity {
        /** 疏朗排版 */
        SPARSE("疏朗"),
        /** 适中排版 */
        MODERATE("适中"),
        /** 紧凑排版 */
        DENSE("紧凑");

        private final String label;

        LayoutDensity(String label) {
            this.label = label;
        }

        /** 中文标签。 */
        public String label() {
            return label;
        }
    }

    /**
     * 视觉风格特征：刻画作者在标题排版、封面色调、配图风格、排版密度上的视觉偏好。
     *
     * <p>视觉风格难以仅凭正文文本可靠检测，启发式分析给出默认推断，建议通过 LLM 或图片分析增强。
     *
     * @param titleLayoutPreference 标题排版偏好（自由文本描述，如「短标题居中加大」）
     * @param coverTone             封面色调倾向
     * @param illustrationStyle     配图风格
     * @param layoutDensity         排版密度
     */
    public record VisualStyle(
            String titleLayoutPreference,
            CoverTone coverTone,
            IllustrationStyle illustrationStyle,
            LayoutDensity layoutDensity
    ) implements StyleDimension {

        /** 紧凑构造器：空值兜底。 */
        public VisualStyle {
            if (titleLayoutPreference == null || titleLayoutPreference.isBlank()) {
                titleLayoutPreference = "默认";
            }
            if (coverTone == null) coverTone = CoverTone.NEUTRAL;
            if (illustrationStyle == null) illustrationStyle = IllustrationStyle.MINIMAL;
            if (layoutDensity == null) layoutDensity = LayoutDensity.MODERATE;
        }

        /** 创建默认视觉风格。 */
        public static VisualStyle empty() {
            return new VisualStyle(null, null, null, null);
        }

        /** 视觉风格在综合相似度中的权重。 */
        @Override
        public double weight() {
            return 0.20;
        }

        /**
         * 按权重合并两个视觉风格：分类字段加权投票，排版偏好取权重较大一方。
         */
        public VisualStyle merge(VisualStyle other, double w1, double w2) {
            if (other == null) {
                return this;
            }
            return new VisualStyle(
                    w1 >= w2 ? titleLayoutPreference : other.titleLayoutPreference,
                    w1 >= w2 ? coverTone : other.coverTone,
                    w1 >= w2 ? illustrationStyle : other.illustrationStyle,
                    w1 >= w2 ? layoutDensity : other.layoutDensity
            );
        }

        /**
         * 计算两个视觉风格的相似度（0~1）：排版偏好文本相似度 + 3 个分类字段匹配，取均值。
         */
        public double similarity(VisualStyle other) {
            if (other == null) {
                return 0.0;
            }
            double sum = 0;
            sum += textSim(titleLayoutPreference, other.titleLayoutPreference);
            sum += coverTone == other.coverTone ? 1 : 0;
            sum += illustrationStyle == other.illustrationStyle ? 1 : 0;
            sum += layoutDensity == other.layoutDensity ? 1 : 0;
            return sum / 4.0;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  聚合 / 合并 / 相似度
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建一个全维空画像（用于兜底）。
     *
     * @return 空风格画像
     */
    public static StyleProfile empty() {
        return new StyleProfile(null, LanguageStyle.empty(), StructureStyle.empty(),
                ContentStyle.empty(), VisualStyle.empty(), 1, null, null);
    }

    /**
     * 便捷工厂：基于单篇内容的四维特征构建画像（accountId 为空，样本数 1）。
     *
     * @param languageStyle  语言风格
     * @param structureStyle 结构风格
     * @param contentStyle   内容特征
     * @param visualStyle    视觉风格
     * @return 单篇内容的风格画像
     */
    public static StyleProfile forContent(LanguageStyle languageStyle,
                                          StructureStyle structureStyle,
                                          ContentStyle contentStyle,
                                          VisualStyle visualStyle) {
        return new StyleProfile(null, languageStyle, structureStyle,
                contentStyle, visualStyle, 1, null, null);
    }

    /**
     * 将多篇内容的画像归并为一个完整画像（按样本数加权，顺序无关）。
     *
     * @param profiles 多篇内容的画像列表
     * @return 聚合后的完整画像；入参为空时返回空画像
     */
    public static StyleProfile aggregate(List<StyleProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return empty();
        }
        StyleProfile acc = profiles.get(0);
        for (int i = 1; i < profiles.size(); i++) {
            acc = acc.merge(profiles.get(i));
        }
        return acc;
    }

    /**
     * 返回带有指定账号 ID 的新画像（用于为聚合结果打标）。
     *
     * @param newAccountId 账号 ID
     * @return 带 accountId 的新画像
     */
    public StyleProfile withAccountId(String newAccountId) {
        return new StyleProfile(newAccountId, languageStyle, structureStyle,
                contentStyle, visualStyle, sampleCount, createdAt, Instant.now());
    }

    /**
     * 按样本数加权聚合当前画像与另一篇画像，用于增量更新与批量建模。
     *
     * <p>加权方式：各数值字段按 {@code sampleCount} 比例加权平均；分类字段取权重较大一方的取值
     * （等价于加权投票）。合并后样本数为两者之和。
     *
     * @param other 另一篇画像
     * @return 合并后的新画像
     */
    public StyleProfile merge(StyleProfile other) {
        if (other == null) {
            return this;
        }
        int total = this.sampleCount + other.sampleCount;
        double w1 = (double) this.sampleCount / total;
        double w2 = (double) other.sampleCount / total;
        return new StyleProfile(
                this.accountId != null ? this.accountId : other.accountId,
                this.languageStyle.merge(other.languageStyle, w1, w2),
                this.structureStyle.merge(other.structureStyle, w1, w2),
                this.contentStyle.merge(other.contentStyle, w1, w2),
                this.visualStyle.merge(other.visualStyle, w1, w2),
                total,
                this.createdAt,
                Instant.now()
        );
    }

    /**
     * 计算当前画像与另一画像的综合相似度（0.0~1.0）。
     *
     * <p>按各维度 {@link StyleDimension#weight()} 加权汇总四个维度的相似度，权重总和归一化。
     * 用于「查找风格相似账号」场景。
     *
     * @param other 另一画像
     * @return 综合相似度，0 表示完全不相似，1 表示完全一致
     */
    public double similarityScore(StyleProfile other) {
        if (other == null) {
            return 0.0;
        }
        double wL = languageStyle.weight();
        double wS = structureStyle.weight();
        double wC = contentStyle.weight();
        double wV = visualStyle.weight();
        double weightSum = wL + wS + wC + wV;
        if (weightSum <= 0) {
            return 0.0;
        }
        double weighted = wL * languageStyle.similarity(other.languageStyle)
                + wS * structureStyle.similarity(other.structureStyle)
                + wC * contentStyle.similarity(other.contentStyle)
                + wV * visualStyle.similarity(other.visualStyle);
        return clamp01(weighted / weightSum);
    }

    // ════════════════════════════════════════════════════════════════
    //  相似度计算工具方法
    // ════════════════════════════════════════════════════════════════

    /** 将数值限制在 [0,1] 区间。 */
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** [0,1] 区间字段的相似度：1 - |a-b|。 */
    private static double ratioSim(double a, double b) {
        return clamp01(1.0 - Math.abs(a - b));
    }

    /** 量纲字段的相似度：以 range 为基准做归一化差异。 */
    private static double rangeSim(double a, double b, double range) {
        if (range <= 0) {
            return a == b ? 1.0 : 0.0;
        }
        return clamp01(1.0 - Math.abs(a - b) / range);
    }

    /** 自由文本字段的相似度：完全相同 1，均非空 0.5，否则 0。 */
    private static double textSim(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.equals(b)) {
            return 1.0;
        }
        if (!a.isBlank() && !b.isBlank()) {
            return 0.5;
        }
        return 0.0;
    }
}
