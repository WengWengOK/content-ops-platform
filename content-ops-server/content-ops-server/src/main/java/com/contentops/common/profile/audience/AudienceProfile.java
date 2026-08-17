package com.contentops.common.profile.audience;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 受众画像数据结构（P1 用户画像扩展系统核心模型）。
 *
 * <p>本类是对 {@link com.contentops.common.dto.TaskContext.AccountProfile} 的结构化扩展。
 * 原始 AccountProfile 仅保留 7 个 String 字段（accountName、niche、targetAudience 等）以保证向后兼容，
 * 本类则在 {@code com.contentops.common.profile.audience} 包下提供多维结构化的受众画像，
 * 不修改 TaskContext，实现无侵入式扩展。
 *
 * <p>受众画像由三层组成，每层都是一个不可变的嵌套 {@code record}，并共同实现密封接口
 * {@link AudienceLayer}，便于在 {@link ProfileEnricher} 中通过 switch 模式匹配做穷尽性渲染：
 * <ul>
 *   <li>{@link DemographicProfile} —— 人口属性：粉丝量级、性别分布、地域分布 TOP5、年龄区间估算</li>
 *   <li>{@link BehaviorProfile} —— 行为偏好：活跃时段分布、内容偏好标签、互动倾向</li>
 *   <li>{@link GrowthProfile} —— 增长态势：7/30 日净增粉丝、增长率、增长渠道分布、增长趋势</li>
 * </ul>
 *
 * <p>所有比率型字段均归一化到 {@code [0,1]} 区间，便于跨维度加权与摘要生成。
 *
 * @see DemographicProfile
 * @see BehaviorProfile
 * @see GrowthProfile
 * @see ProfileEnricher
 */
public record AudienceProfile(
        /** 账号 ID */
        String accountId,
        /** 人口属性画像 */
        DemographicProfile demographic,
        /** 行为偏好画像 */
        BehaviorProfile behavior,
        /** 增长态势画像 */
        GrowthProfile growth,
        /** 画像构建时间 */
        Instant createdAt,
        /** 画像最后更新时间 */
        Instant updatedAt
) {

    /**
     * 紧凑构造器：对入参做空值兜底，保证画像对象始终处于合法状态。
     */
    public AudienceProfile {
        if (demographic == null) demographic = DemographicProfile.empty();
        if (behavior == null) behavior = BehaviorProfile.empty();
        if (growth == null) growth = GrowthProfile.empty();
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
    public sealed interface AudienceLayer permits DemographicProfile, BehaviorProfile, GrowthProfile {
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
    // 第一层：人口属性画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 人口属性画像层 —— 刻画受众的基础人口统计特征。
     *
     * @param followerCount   粉丝量级
     * @param genderDistribution 性别分布
     * @param regions         地域分布 TOP5（按占比降序）
     * @param ageDistribution 年龄区间估算
     */
    public record DemographicProfile(
            long followerCount,
            GenderDistribution genderDistribution,
            List<RegionStat> regions,
            AgeDistribution ageDistribution
    ) implements AudienceLayer {

        /** 紧凑构造器：空值兜底。 */
        public DemographicProfile {
            if (genderDistribution == null) genderDistribution = GenderDistribution.empty();
            if (regions == null) regions = List.of();
            if (ageDistribution == null) ageDistribution = AgeDistribution.empty();
        }

        @Override
        public String layerName() {
            return "人口属性";
        }

        @Override
        public boolean hasData() {
            return followerCount > 0
                    || genderDistribution.hasData()
                    || !regions.isEmpty()
                    || ageDistribution.hasData();
        }

        /**
         * 构造一个空的人口属性画像（用于降级场景）。
         *
         * @return 字段全部为默认值的空画像
         */
        public static DemographicProfile empty() {
            return new DemographicProfile(0, GenderDistribution.empty(), List.of(), AgeDistribution.empty());
        }
    }

    /**
     * 性别分布 —— 男性、女性、未知性别占比。
     *
     * <p>三个比率之和应接近 1.0；构造器不强制求和校验，允许平台数据存在小误差。
     *
     * @param maleRatio    男性占比（0~1）
     * @param femaleRatio  女性占比（0~1）
     * @param unknownRatio 未知性别占比（0~1）
     */
    public record GenderDistribution(
            double maleRatio,
            double femaleRatio,
            double unknownRatio
    ) {

        /** 紧凑构造器：归一化到 [0,1]。 */
        public GenderDistribution {
            maleRatio = clamp01(maleRatio);
            femaleRatio = clamp01(femaleRatio);
            unknownRatio = clamp01(unknownRatio);
        }

        /**
         * 构造空的性别分布。
         *
         * @return 全零的性别分布
         */
        public static GenderDistribution empty() {
            return new GenderDistribution(0, 0, 0);
        }

        /**
         * 判断是否有有效数据。
         *
         * @return 任一比率大于 0 即为 true
         */
        public boolean hasData() {
            return maleRatio > 0 || femaleRatio > 0 || unknownRatio > 0;
        }
    }

    /**
     * 地域统计 —— 单个地域的占比与粉丝数。
     *
     * @param region 地域名称（如「广东」「北京」）
     * @param ratio  占比（0~1）
     * @param count  该地域粉丝数
     */
    public record RegionStat(
            String region,
            double ratio,
            long count
    ) {

        /** 紧凑构造器：归一化与空值兜底。 */
        public RegionStat {
            if (region == null || region.isBlank()) region = "未知";
            ratio = clamp01(ratio);
            count = Math.max(0, count);
        }
    }

    /**
     * 年龄区间估算 —— 各年龄段的占比分布。
     *
     * <p>key 为年龄段字符串（如 {@code "18-24"}），value 为该段占比（0~1）。
     *
     * @param ranges 年龄段 -> 占比的映射
     */
    public record AgeDistribution(
            Map<String, Double> ranges
    ) {

        /** 紧凑构造器：空值兜底。 */
        public AgeDistribution {
            if (ranges == null) ranges = Map.of();
        }

        /**
         * 构造空的年龄分布。
         *
         * @return 空映射
         */
        public static AgeDistribution empty() {
            return new AgeDistribution(Map.of());
        }

        /**
         * 判断是否有有效数据。
         *
         * @return ranges 非空且至少有一个占比大于 0 即为 true
         */
        public boolean hasData() {
            return ranges != null && !ranges.isEmpty()
                    && ranges.values().stream().anyMatch(v -> v > 0);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 第二层：行为偏好画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 行为偏好画像层 —— 刻画受众的活跃时段、内容偏好与互动习惯。
     *
     * @param activeTimeSlots    活跃时段分布（按活动水平降序）
     * @param tagPreferences     内容偏好标签列表（按权重降序）
     * @param interactionTendency 互动倾向
     */
    public record BehaviorProfile(
            List<TimeSlotActivity> activeTimeSlots,
            List<TagPreference> tagPreferences,
            InteractionTendency interactionTendency
    ) implements AudienceLayer {

        /** 紧凑构造器：空值兜底。 */
        public BehaviorProfile {
            if (activeTimeSlots == null) activeTimeSlots = List.of();
            if (tagPreferences == null) tagPreferences = List.of();
            if (interactionTendency == null) interactionTendency = InteractionTendency.empty();
        }

        @Override
        public String layerName() {
            return "行为偏好";
        }

        @Override
        public boolean hasData() {
            return !activeTimeSlots.isEmpty()
                    || !tagPreferences.isEmpty()
                    || interactionTendency.hasData();
        }

        /**
         * 构造一个空的行为偏好画像。
         *
         * @return 空画像
         */
        public static BehaviorProfile empty() {
            return new BehaviorProfile(List.of(), List.of(), InteractionTendency.empty());
        }
    }

    /**
     * 活跃时段活动 —— 单个时段的活跃水平与平均互动率。
     *
     * @param timeSlot         时段标识（如 {@code "20:00-22:00"}）
     * @param activityLevel    活动水平（0~1，越高越活跃）
     * @param avgEngagementRate 该时段平均互动率（0~1）
     */
    public record TimeSlotActivity(
            String timeSlot,
            double activityLevel,
            double avgEngagementRate
    ) {

        /** 紧凑构造器：归一化与空值兜底。 */
        public TimeSlotActivity {
            if (timeSlot == null || timeSlot.isBlank()) timeSlot = "未知时段";
            activityLevel = clamp01(activityLevel);
            avgEngagementRate = clamp01(avgEngagementRate);
        }
    }

    /**
     * 内容偏好标签 —— 单个标签的偏好权重与出现次数。
     *
     * @param tag             标签名称（如「个人成长」）
     * @param weight          偏好权重（0~1，越高越偏好）
     * @param occurrenceCount 该标签在历史内容中的出现次数
     */
    public record TagPreference(
            String tag,
            double weight,
            int occurrenceCount
    ) {

        /** 紧凑构造器：归一化与空值兜底。 */
        public TagPreference {
            if (tag == null || tag.isBlank()) tag = "未知标签";
            weight = clamp01(weight);
            occurrenceCount = Math.max(0, occurrenceCount);
        }
    }

    /**
     * 互动倾向 —— 评论、收藏、分享、点赞四种互动行为的占比。
     *
     * <p>四个比率之和应接近 1.0；构造器不强制求和校验。
     *
     * @param commentRatio 评论占比（0~1）
     * @param collectRatio 收藏占比（0~1）
     * @param shareRatio   分享占比（0~1）
     * @param likeRatio    点赞占比（0~1）
     */
    public record InteractionTendency(
            double commentRatio,
            double collectRatio,
            double shareRatio,
            double likeRatio
    ) {

        /** 紧凑构造器：归一化到 [0,1]。 */
        public InteractionTendency {
            commentRatio = clamp01(commentRatio);
            collectRatio = clamp01(collectRatio);
            shareRatio = clamp01(shareRatio);
            likeRatio = clamp01(likeRatio);
        }

        /**
         * 构造空的互动倾向。
         *
         * @return 全零的互动倾向
         */
        public static InteractionTendency empty() {
            return new InteractionTendency(0, 0, 0, 0);
        }

        /**
         * 判断是否有有效数据。
         *
         * @return 任一比率大于 0 即为 true
         */
        public boolean hasData() {
            return commentRatio > 0 || collectRatio > 0 || shareRatio > 0 || likeRatio > 0;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 第三层：增长态势画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 增长态势画像层 —— 刻画粉丝增长的速度、渠道与趋势。
     *
     * @param netGrowth7d        7 日净增粉丝数
     * @param netGrowth30d       30 日净增粉丝数
     * @param followerGrowthRate 粉丝增长率（小数，如 0.032 表示 3.2%）
     * @param growthChannels     增长渠道分布（渠道名 -> 占比，如 {@code {"搜索":0.3, "推荐":0.5, "分享":0.2}}）
     * @param growthTrend        增长趋势
     */
    public record GrowthProfile(
            long netGrowth7d,
            long netGrowth30d,
            double followerGrowthRate,
            Map<String, Double> growthChannels,
            GrowthTrend growthTrend
    ) implements AudienceLayer {

        /** 紧凑构造器：空值兜底。 */
        public GrowthProfile {
            if (growthChannels == null) growthChannels = Map.of();
            if (growthTrend == null) growthTrend = GrowthTrend.STEADY;
        }

        @Override
        public String layerName() {
            return "增长态势";
        }

        @Override
        public boolean hasData() {
            return netGrowth7d != 0
                    || netGrowth30d != 0
                    || followerGrowthRate != 0
                    || (growthChannels != null && !growthChannels.isEmpty());
        }

        /**
         * 构造一个空的增态势画像。
         *
         * @return 空画像
         */
        public static GrowthProfile empty() {
            return new GrowthProfile(0, 0, 0, Map.of(), GrowthTrend.STEADY);
        }
    }

    /**
     * 增长趋势枚举 —— 描述粉丝增长的近期变化方向。
     */
    public enum GrowthTrend {
        /** 快速增长 */
        RAPID_GROWTH("快速增长"),
        /** 稳定增长 */
        STEADY("稳定增长"),
        /** 增长放缓 */
        SLOWDOWN("增长放缓"),
        /** 粉丝流失 */
        DECLINE("粉丝流失");

        private final String label;

        GrowthTrend(String label) {
            this.label = label;
        }

        /**
         * 获取趋势的中文标签。
         *
         * @return 中文标签
         */
        public String label() {
            return label;
        }

        /**
         * 根据增长率自动判定趋势方向。
         *
         * @param rate      增长率（小数，正为增长）
         * @param rapidThreshold 快速增长判定阈值（超过该值视为快速增长）
         * @param declineThreshold 流失判定阈值（低于该负值视为流失）
         * @return 对应趋势
         */
        public static GrowthTrend fromRate(double rate, double rapidThreshold, double declineThreshold) {
            if (rate >= rapidThreshold) {
                return RAPID_GROWTH;
            } else if (rate <= declineThreshold) {
                return DECLINE;
            } else if (rate < declineThreshold / 2) {
                return SLOWDOWN;
            }
            return STEADY;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工厂方法与合并逻辑
    // ════════════════════════════════════════════════════════════════

    /**
     * 构造一个全层空的受众画像（用于降级或占位场景）。
     *
     * @param accountId 账号 ID
     * @return 三层均为空的画像
     */
    public static AudienceProfile empty(String accountId) {
        return new AudienceProfile(accountId, DemographicProfile.empty(),
                BehaviorProfile.empty(), GrowthProfile.empty(), null, null);
    }

    /**
     * 返回带有指定账号 ID 的新画像（用于为构建结果打标）。
     *
     * @param newAccountId 账号 ID
     * @return 带 accountId 的新画像
     */
    public AudienceProfile withAccountId(String newAccountId) {
        return new AudienceProfile(newAccountId, demographic, behavior, growth,
                createdAt, Instant.now());
    }

    /**
     * 判断画像是否有有效数据。
     *
     * <p>三层中任一层包含有效数据即视为有效。用于区分「真实画像」与「降级空画像」。
     *
     * @return true 表示画像包含至少一层有效数据
     */
    public boolean hasData() {
        return demographic.hasData() || behavior.hasData() || growth.hasData();
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
    public static String describeLayer(AudienceLayer layer) {
        return switch (layer) {
            case DemographicProfile d -> "人口属性：粉丝" + formatCount(d.followerCount())
                    + "，女性" + formatPercent(d.genderDistribution().femaleRatio())
                    + "，男性" + formatPercent(d.genderDistribution().maleRatio());
            case BehaviorProfile b -> "行为偏好：活跃时段" + b.activeTimeSlots().size()
                    + "个，偏好标签" + b.tagPreferences().size() + "个";
            case GrowthProfile g -> "增长态势：30日净增" + g.netGrowth30d()
                    + "，增长率" + formatPercent(g.followerGrowthRate())
                    + "，趋势" + g.growthTrend().label();
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

    /** 将粉丝量级格式化为可读字符串（万级以上用「万」单位）。 */
    private static String formatCount(long count) {
        if (count >= 10000) {
            return String.format("%.1f万", count / 10000.0);
        }
        return String.valueOf(count);
    }
}
