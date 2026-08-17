package com.contentops.common.profile.audience;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 内容画像数据结构（P1 用户画像扩展系统 —— 创作者侧内容特征画像）。
 *
 * <p>本类描述创作者侧的「写什么」内容特征画像，与 {@link com.contentops.common.profile.style.StyleProfile}
 * 互补：风格画像关注「怎么写」（语言、结构、视觉），内容画像关注「写什么」（选题、表现、变现）。
 *
 * <p>内容画像由三层组成，每层都是一个不可变的嵌套 {@code record}，并共同实现密封接口
 * {@link ContentLayer}，便于在 {@link ProfileEnricher} 中通过 switch 模式匹配做穷尽性渲染：
 * <ul>
 *   <li>{@link TopicDistribution} —— 选题分布：历史选题 TOP10 关键词、内容类型配比、平台适配度</li>
 *   <li>{@link PerformanceHistory} —— 历史表现：平均互动率、平均阅读量、最佳发文时段、高/低表现特征归因</li>
 *   <li>{@link MonetizationProfile} —— 变现画像：变现方式、品牌合作信息、内容商业化率</li>
 * </ul>
 *
 * <p>所有比率型字段均归一化到 {@code [0,1]} 区间，便于跨维度加权与摘要生成。
 *
 * @see TopicDistribution
 * @see PerformanceHistory
 * @see MonetizationProfile
 * @see ProfileEnricher
 */
public record ContentProfile(
        /** 账号 ID */
        String accountId,
        /** 选题分布画像 */
        TopicDistribution topicDistribution,
        /** 历史表现画像 */
        PerformanceHistory performanceHistory,
        /** 变现画像 */
        MonetizationProfile monetization,
        /** 画像构建时间 */
        Instant createdAt,
        /** 画像最后更新时间 */
        Instant updatedAt
) {

    /**
     * 紧凑构造器：对入参做空值兜底，保证画像对象始终处于合法状态。
     */
    public ContentProfile {
        if (topicDistribution == null) topicDistribution = TopicDistribution.empty();
        if (performanceHistory == null) performanceHistory = PerformanceHistory.empty();
        if (monetization == null) monetization = MonetizationProfile.empty();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    /**
     * 画像层级密封接口，限定只有三个内置层可实现。
     *
     * <p>配合 switch 模式匹配可实现穷尽性分发，编译器保证所有层级均被覆盖，
     * 新增层级必须显式声明，避免遗漏处理（Java 21 特性）。
     */
    public sealed interface ContentLayer permits TopicDistribution, PerformanceHistory, MonetizationProfile {
        /**
         * 获取该画像层的语义名称（用于展示与日志）。
         *
         * @return 层级名称
         */
        String layerName();

        /**
         * 判断该层是否有有效数据。
         *
         * @return true 表示该层包含至少一个有效字段
         */
        boolean hasData();
    }

    // ════════════════════════════════════════════════════════════════
    // 第一层：选题分布
    // ════════════════════════════════════════════════════════════════

    /**
     * 选题分布画像层 —— 刻画创作者历史选题的关键词、内容类型配比与平台适配度。
     *
     * @param topicKeywords 历史选题 TOP10 关键词（按频率降序）
     * @param contentTypeRatio 内容类型配比（类型 -> 占比）
     * @param platformFits  平台适配度列表
     */
    public record TopicDistribution(
            List<TopicKeyword> topicKeywords,
            Map<ContentType, Double> contentTypeRatio,
            List<PlatformFit> platformFits
    ) implements ContentLayer {

        /** 紧凑构造器：空值兜底。 */
        public TopicDistribution {
            if (topicKeywords == null) topicKeywords = List.of();
            if (contentTypeRatio == null) contentTypeRatio = Map.of();
            if (platformFits == null) platformFits = List.of();
        }

        @Override
        public String layerName() {
            return "选题分布";
        }

        @Override
        public boolean hasData() {
            return !topicKeywords.isEmpty()
                    || !contentTypeRatio.isEmpty()
                    || !platformFits.isEmpty();
        }

        /**
         * 构造一个空的选题分布画像。
         *
         * @return 空画像
         */
        public static TopicDistribution empty() {
            return new TopicDistribution(List.of(), Map.of(), List.of());
        }
    }

    /**
     * 选题关键词 —— 单个关键词的频率、最后使用日期与平均表现。
     *
     * @param keyword        关键词
     * @param frequency      出现频率（历史内容中出现次数）
     * @param lastUsedDate   最后使用日期
     * @param avgPerformance 平均表现（0~1，综合互动率与阅读量的归一化得分）
     */
    public record TopicKeyword(
            String keyword,
            int frequency,
            LocalDate lastUsedDate,
            double avgPerformance
    ) {

        /** 紧凑构造器：归一化与空值兜底。 */
        public TopicKeyword {
            if (keyword == null || keyword.isBlank()) keyword = "未知";
            frequency = Math.max(0, frequency);
            avgPerformance = clamp01(avgPerformance);
        }
    }

    /**
     * 内容类型枚举 —— 创作者常见的内容类型分类。
     */
    public enum ContentType {
        /** 干货教程类 */
        TUTORIAL("干货教程"),
        /** 个人故事类 */
        PERSONAL_STORY("个人故事"),
        /** 热点解读类 */
        TREND_ANALYSIS("热点解读"),
        /** 清单盘点类 */
        LISTICLE("清单盘点"),
        /** 观点输出类 */
        OPINION("观点输出");

        private final String label;

        ContentType(String label) {
            this.label = label;
        }

        /**
         * 获取内容类型的中文标签。
         *
         * @return 中文标签
         */
        public String label() {
            return label;
        }
    }

    /**
     * 平台适配度 —— 单个平台的平均阅读量、互动率与内容数量。
     *
     * @param platform          平台名称（如「公众号」「小红书」）
     * @param avgReadCount      平均阅读量
     * @param avgEngagementRate 平均互动率（0~1）
     * @param contentCount      该平台发布的内容数量
     */
    public record PlatformFit(
            String platform,
            long avgReadCount,
            double avgEngagementRate,
            int contentCount
    ) {

        /** 紧凑构造器：归一化与空值兜底。 */
        public PlatformFit {
            if (platform == null || platform.isBlank()) platform = "未知平台";
            avgReadCount = Math.max(0, avgReadCount);
            avgEngagementRate = clamp01(avgEngagementRate);
            contentCount = Math.max(0, contentCount);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 第二层：历史表现
    // ════════════════════════════════════════════════════════════════

    /**
     * 历史表现画像层 —— 刻画创作者历史内容的互动率、阅读量与表现归因。
     *
     * @param avgEngagementRate      历史平均互动率（0~1）
     * @param avgReadCount           历史平均阅读量
     * @param bestPublishTimeSlot    最佳发文时段（如 {@code "20:00-22:00"}）
     * @param highPerformanceTraits  高表现内容特征列表（如「标题含数字」「开头讲故事」）
     * @param lowPerformanceCauses   低表现内容归因列表（如「发布时间非黄金时段」）
     */
    public record PerformanceHistory(
            double avgEngagementRate,
            long avgReadCount,
            String bestPublishTimeSlot,
            List<String> highPerformanceTraits,
            List<String> lowPerformanceCauses
    ) implements ContentLayer {

        /** 紧凑构造器：归一化与空值兜底。 */
        public PerformanceHistory {
            avgEngagementRate = clamp01(avgEngagementRate);
            avgReadCount = Math.max(0, avgReadCount);
            if (bestPublishTimeSlot == null || bestPublishTimeSlot.isBlank()) {
                bestPublishTimeSlot = "未知";
            }
            if (highPerformanceTraits == null) highPerformanceTraits = List.of();
            if (lowPerformanceCauses == null) lowPerformanceCauses = List.of();
        }

        @Override
        public String layerName() {
            return "历史表现";
        }

        @Override
        public boolean hasData() {
            return avgEngagementRate > 0
                    || avgReadCount > 0
                    || !bestPublishTimeSlot.equals("未知")
                    || !highPerformanceTraits.isEmpty()
                    || !lowPerformanceCauses.isEmpty();
        }

        /**
         * 构造一个空的历史表现画像。
         *
         * @return 空画像
         */
        public static PerformanceHistory empty() {
            return new PerformanceHistory(0, 0, null, List.of(), List.of());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 第三层：变现画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 变现画像层 —— 刻画创作者的变现方式、品牌合作与商业化程度。
     *
     * @param monetizationTypes   变现方式列表
     * @param brandCollaborations 品牌合作信息列表
     * @param commercializationRate 内容商业化率（0~1，商业化内容占总内容的比例）
     */
    public record MonetizationProfile(
            List<MonetizationType> monetizationTypes,
            List<BrandCollab> brandCollaborations,
            double commercializationRate
    ) implements ContentLayer {

        /** 紧凑构造器：归一化与空值兜底。 */
        public MonetizationProfile {
            if (monetizationTypes == null) monetizationTypes = List.of();
            if (brandCollaborations == null) brandCollaborations = List.of();
            commercializationRate = clamp01(commercializationRate);
        }

        @Override
        public String layerName() {
            return "变现画像";
        }

        @Override
        public boolean hasData() {
            return !monetizationTypes.isEmpty()
                    || !brandCollaborations.isEmpty()
                    || commercializationRate > 0;
        }

        /**
         * 构造一个空的变现画像。
         *
         * @return 空画像
         */
        public static MonetizationProfile empty() {
            return new MonetizationProfile(List.of(), List.of(), 0);
        }
    }

    /**
     * 变现方式枚举 —— 创作者常见的商业化路径。
     */
    public enum MonetizationType {
        /** 广告收入 */
        AD_REVENUE("广告收入"),
        /** 付费内容 */
        PAID_CONTENT("付费内容"),
        /** 品牌合作 */
        BRAND_COLLAB("品牌合作"),
        /** 电商带货 */
        ECOMMERCE("电商带货"),
        /** 课程售卖 */
        COURSE("课程售卖"),
        /** 打赏赞赏 */
        TIPPING("打赏赞赏");

        private final String label;

        MonetizationType(String label) {
            this.label = label;
        }

        /**
         * 获取变现方式的中文标签。
         *
         * @return 中文标签
         */
        public String label() {
            return label;
        }
    }

    /**
     * 品牌合作信息 —— 单次品牌合作的详情。
     *
     * @param brandName         品牌名称
     * @param collaborationType 合作类型（如「软文植入」「测评」「代言」）
     * @param date              合作日期
     */
    public record BrandCollab(
            String brandName,
            String collaborationType,
            LocalDate date
    ) {

        /** 紧凑构造器：空值兜底。 */
        public BrandCollab {
            if (brandName == null || brandName.isBlank()) brandName = "未知品牌";
            if (collaborationType == null || collaborationType.isBlank()) collaborationType = "未知";
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工厂方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 构造一个全层空的内容画像（用于降级或占位场景）。
     *
     * @param accountId 账号 ID
     * @return 三层均为空的画像
     */
    public static ContentProfile empty(String accountId) {
        return new ContentProfile(accountId, TopicDistribution.empty(),
                PerformanceHistory.empty(), MonetizationProfile.empty(), null, null);
    }

    /**
     * 返回带有指定账号 ID 的新画像（用于为构建结果打标）。
     *
     * @param newAccountId 账号 ID
     * @return 带 accountId 的新画像
     */
    public ContentProfile withAccountId(String newAccountId) {
        return new ContentProfile(newAccountId, topicDistribution, performanceHistory,
                monetization, createdAt, Instant.now());
    }

    /**
     * 判断画像是否有有效数据。
     *
     * <p>三层中任一层包含有效数据即视为有效。用于区分「真实画像」与「降级空画像」。
     *
     * @return true 表示画像包含至少一层有效数据
     */
    public boolean hasData() {
        return topicDistribution.hasData() || performanceHistory.hasData() || monetization.hasData();
    }

    // ════════════════════════════════════════════════════════════════
    // 密封接口的穷尽性分发示例（Java 21 switch 模式匹配）
    // ════════════════════════════════════════════════════════════════

    /**
     * 基于密封接口的穷尽性分发 —— 返回指定画像层的简短描述。
     *
     * <p>利用 Java 21 switch 模式匹配 + 密封接口，编译器保证所有层级均被覆盖，
     * 无需 default 分支；新增层级时编译器会强制要求补充分支，避免遗漏。
     *
     * @param layer 画像层实例
     * @return 该层的简短描述文本
     */
    public static String describeLayer(ContentLayer layer) {
        return switch (layer) {
            case TopicDistribution t -> "选题分布：关键词" + t.topicKeywords().size()
                    + "个，内容类型" + t.contentTypeRatio().size() + "种"
                    + "，平台" + t.platformFits().size() + "个";
            case PerformanceHistory p -> "历史表现：平均互动率" + formatPercent(p.avgEngagementRate())
                    + "，平均阅读" + p.avgReadCount()
                    + "，最佳时段" + p.bestPublishTimeSlot();
            case MonetizationProfile m -> "变现画像：方式" + m.monetizationTypes().size()
                    + "种，品牌合作" + m.brandCollaborations().size()
                    + "次，商业化率" + formatPercent(m.commercializationRate());
        };
    }

    // ──────────────────── 内部工具方法 ────────────────────

    /** 将数值限制在 [0,1] 区间。 */
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 将小数格式化为百分比字符串（保留一位小数）。 */
    private static String formatPercent(double value) {
        return String.format("%.1f%%", value * 100);
    }
}
