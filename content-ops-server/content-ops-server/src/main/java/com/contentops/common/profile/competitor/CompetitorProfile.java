package com.contentops.common.profile.competitor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 竞品画像数据结构（P0 定向竞品监控核心模型）。
 *
 * <p>从通用搜索升级为定向竞品监控的核心载体，采用「四层画像」结构对单个竞品账号
 * 进行全方位刻画：基础属性、内容特征、表现表现、与我方账号的对标关系。每一层均由
 * 不可变的 {@link record} 实现，保证画像在并发读取场景下的线程安全性。
 *
 * <p>四层画像均实现密封接口 {@link ProfileLayer}，便于通过 switch 模式匹配做穷尽性
 * 分发处理（Java 21 特性）。
 *
 * <ul>
 *   <li>{@link BasicProfile} —— 基础画像：账号身份与量级属性</li>
 *   <li>{@link ContentProfile} —— 内容画像：近 30 天 TOP 作品与风格特征</li>
 *   <li>{@link PerformanceProfile} —— 表现画像：互动率、爆款率与增长趋势</li>
 *   <li>{@link ComparisonProfile} —— 对标画像：与我方账号的差距与策略</li>
 * </ul>
 *
 * <p>通过 {@link #merge(CompetitorProfile)} 方法支持增量更新：新画像覆盖旧画像中
 * 已刷新的字段，同时保留原始创建时间并递增版本号，用于监控任务的变更溯源。
 *
 * @param competitorAccountId 竞品账号唯一标识（平台内 open_id / 账号 ID）
 * @param platform            所属平台（wechat / xiaohongshu / douyin / bilibili / kuaishou）
 * @param niche               所属领域 / 赛道（如「母婴」「科技数码」）
 * @param basic               基础画像层
 * @param content             内容画像层
 * @param performance         表现画像层
 * @param comparison          对标画像层
 * @param createdAt           画像首次创建时间
 * @param lastUpdated         画像最近更新时间
 * @param version             画像版本号，每次 merge 自增
 */
public record CompetitorProfile(
        String competitorAccountId,
        String platform,
        String niche,
        BasicProfile basic,
        ContentProfile content,
        PerformanceProfile performance,
        ComparisonProfile comparison,
        Instant createdAt,
        Instant lastUpdated,
        int version
) {

    // ════════════════════════════════════════════════════════════════
    // 密封接口：四层画像的统一抽象
    // ════════════════════════════════════════════════════════════════

    /**
     * 画像层级的密封接口，限定只有四个内置层可实现。
     *
     * <p>配合 switch 模式匹配可实现穷尽性分发，新增层级必须显式声明，避免遗漏处理。
     */
    public sealed interface ProfileLayer permits BasicProfile, ContentProfile, PerformanceProfile, ComparisonProfile {

        /**
         * 获取该画像层的语义名称（用于展示与日志）。
         *
         * @return 层级名称
         */
        String layerName();
    }

    // ════════════════════════════════════════════════════════════════
    // 第一层：基础画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 基础画像层 —— 刻画竞品账号的身份、量级与运营节奏属性。
     *
     * @param competitorAccountId   竞品账号 ID
     * @param platform              所属平台
     * @param niche                 所属领域
     * @param followerCount         粉丝量级
     * @param growthRate30d         近 30 日粉丝增长率（小数，如 0.05 表示 5%）
     * @param postingFrequencyPerWeek 每周发文频率（篇/周）
     * @param bestPublishSlots      最佳发布时段列表（如 ["20:00-22:00", "12:00-13:00"]）
     * @param verificationType      认证类型（如「企业认证」「个人认证」「无认证」）
     * @param tierWeight            等级权重（0.0-1.0，综合影响力权重）
     * @param contentTypeRatio      内容类型配比（类型名称 -> 占比，如 {"图文":0.6, "视频":0.4}）
     */
    public record BasicProfile(
            String competitorAccountId,
            String platform,
            String niche,
            long followerCount,
            double growthRate30d,
            double postingFrequencyPerWeek,
            List<String> bestPublishSlots,
            String verificationType,
            double tierWeight,
            Map<String, Double> contentTypeRatio
    ) implements ProfileLayer {

        @Override
        public String layerName() {
            return "基础画像";
        }

        /**
         * 构造一个空的基础画像（用于降级场景或占位）。
         *
         * @param competitorAccountId 账号 ID
         * @param platform            平台
         * @param niche               领域
         * @return 字段全部为默认值的空基础画像
         */
        public static BasicProfile empty(String competitorAccountId, String platform, String niche) {
            return new BasicProfile(competitorAccountId, platform, niche,
                    0L, 0.0, 0.0, List.of(), "未知", 0.0, Map.of());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 第二层：内容画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 内容画像层 —— 刻画竞品近 30 天的内容特征与风格。
     *
     * @param topWorks       近 30 天 TOP10 作品列表（含标题与互动指标）
     * @param topicKeywords  选题方向关键词列表
     * @param titleStyle     标题风格特征（长度 / 结构 / 情绪词）
     * @param contentStyle   内容风格特征描述
     * @param visualStyle    视觉风格特征描述
     */
    public record ContentProfile(
            List<TopWork> topWorks,
            List<String> topicKeywords,
            TitleStyle titleStyle,
            String contentStyle,
            String visualStyle
    ) implements ProfileLayer {

        @Override
        public String layerName() {
            return "内容画像";
        }

        /**
         * 构造一个空的内容画像。
         *
         * @return 空内容画像
         */
        public static ContentProfile empty() {
            return new ContentProfile(List.of(), List.of(), TitleStyle.empty(), "", "");
        }
    }

    /**
     * TOP 作品记录 —— 单篇高表现作品的标题与核心指标。
     *
     * @param title          作品标题
     * @param publishTime    发布时间（ISO-8601 字符串）
     * @param views          阅读 / 播放量
     * @param likes          点赞数
     * @param comments       评论数
     * @param shares         分享 / 转发数
     * @param engagementRate 互动率（小数，如 0.06 表示 6%）
     */
    public record TopWork(
            String title,
            String publishTime,
            long views,
            long likes,
            long comments,
            long shares,
            double engagementRate
    ) {
    }

    /**
     * 标题风格特征 —— 量化竞品标题的长度、结构与情绪倾向。
     *
     * @param avgLength      标题平均长度（字符数）
     * @param structurePattern 结构模式（如「疑问句」「数字列表」「悬念式」）
     * @param emotionWords   高频情绪词列表
     * @param styleTags      风格标签列表（如「口语化」「专业感」）
     */
    public record TitleStyle(
            double avgLength,
            String structurePattern,
            List<String> emotionWords,
            List<String> styleTags
    ) {

        /**
         * 构造一个空的标题风格特征。
         *
         * @return 空标题风格
         */
        public static TitleStyle empty() {
            return new TitleStyle(0.0, "未知", List.of(), List.of());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 第三层：表现画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 表现画像层 —— 刻画竞品内容的互动表现与增长态势。
     *
     * @param avgEngagementRate          平均互动率（小数）
     * @param medianEngagementRate       中位数互动率（小数，抗爆款极值干扰）
     * @param hitRate                    爆款率（进入 TOP10% 阈值的作品占比，小数）
     * @param platformComparisonMatrix   平台表现对比矩阵（平台名 -> 相对表现分值）
     * @param growthTrend                增长趋势（上升 / 稳定 / 下降）
     * @param highPerformanceCommonTraits 高表现内容共性特征列表
     */
    public record PerformanceProfile(
            double avgEngagementRate,
            double medianEngagementRate,
            double hitRate,
            Map<String, Double> platformComparisonMatrix,
            GrowthTrend growthTrend,
            List<String> highPerformanceCommonTraits
    ) implements ProfileLayer {

        @Override
        public String layerName() {
            return "表现画像";
        }

        /**
         * 构造一个空的表现画像。
         *
         * @return 空表现画像
         */
        public static PerformanceProfile empty() {
            return new PerformanceProfile(0.0, 0.0, 0.0, Map.of(), GrowthTrend.STABLE, List.of());
        }
    }

    /**
     * 增长趋势枚举 —— 描述竞品粉丝或互动的近期变化方向。
     */
    public enum GrowthTrend {
        /** 上升趋势 */
        ASCENDING("上升"),
        /** 稳定 */
        STABLE("稳定"),
        /** 下降趋势 */
        DESCENDING("下降");

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
         * 根据变化率自动判定趋势方向。
         *
         * @param rate      变化率（小数，正为增长）
         * @param threshold 判定阈值（超出该值视为上升 / 下降）
         * @return 对应趋势
         */
        public static GrowthTrend fromRate(double rate, double threshold) {
            if (rate > threshold) {
                return ASCENDING;
            } else if (rate < -threshold) {
                return DESCENDING;
            }
            return STABLE;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 第四层：对标画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 对标画像层 —— 量化竞品与我方账号的差距与可执行策略。
     *
     * @param metricGaps             与我方账号的指标差距量化（指标名 -> 差值，正为竞品领先）
     * @param topicOverlap           选题重叠度（0.0-1.0，Jaccard 系数）
     * @param styleSimilarity        风格相似度（0.0-1.0，向量余弦距离）
     * @param borrowableStrategies   可借鉴策略列表
     * @param avoidDirections        应规避方向列表
     * @param competitionIntensity   竞争烈度评分（0-100，越高竞争越激烈）
     */
    public record ComparisonProfile(
            Map<String, Double> metricGaps,
            double topicOverlap,
            double styleSimilarity,
            List<String> borrowableStrategies,
            List<String> avoidDirections,
            int competitionIntensity
    ) implements ProfileLayer {

        @Override
        public String layerName() {
            return "对标画像";
        }

        /**
         * 构造一个空的对标画像（尚未与我方账号对标时）。
         *
         * @return 空对标画像
         */
        public static ComparisonProfile empty() {
            return new ComparisonProfile(Map.of(), 0.0, 0.0, List.of(), List.of(), 0);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工厂方法与合并逻辑
    // ════════════════════════════════════════════════════════════════

    /**
     * 构造一个仅含基础信息的空竞品画像（用于降级或占位场景）。
     *
     * @param competitorAccountId 账号 ID
     * @param platform            平台
     * @param niche               领域
     * @return 仅基础层填充、其余层为空的画像
     */
    public static CompetitorProfile skeleton(String competitorAccountId, String platform, String niche) {
        Instant now = Instant.now();
        return new CompetitorProfile(
                competitorAccountId, platform, niche,
                BasicProfile.empty(competitorAccountId, platform, niche),
                ContentProfile.empty(),
                PerformanceProfile.empty(),
                ComparisonProfile.empty(),
                now, now, 1
        );
    }

    /**
     * 增量合并画像 —— 将较新的画像数据合并到当前画像上。
     *
     * <p>合并规则：
     * <ul>
     *   <li>身份字段（账号 ID / 平台 / 领域）以新画像为准，新画像为空时保留旧值</li>
     *   <li>四层画像以新画像非 null 为准覆盖，否则保留旧层</li>
     *   <li>{@code createdAt} 始终保留旧值（首次创建时间不可变）</li>
     *   <li>{@code lastUpdated} 取新画像时间</li>
     *   <li>{@code version} 在旧版本基础上 +1</li>
     * </ul>
     *
     * @param newer 较新的竞品画像（不可为 null）
     * @return 合并后的新画像实例
     */
    public CompetitorProfile merge(CompetitorProfile newer) {
        if (newer == null) {
            return this;
        }
        return new CompetitorProfile(
                firstNonBlank(newer.competitorAccountId, this.competitorAccountId),
                firstNonBlank(newer.platform, this.platform),
                firstNonBlank(newer.niche, this.niche),
                newer.basic != null ? newer.basic : this.basic,
                newer.content != null ? newer.content : this.content,
                newer.performance != null ? newer.performance : this.performance,
                newer.comparison != null ? newer.comparison : this.comparison,
                this.createdAt,
                newer.lastUpdated != null ? newer.lastUpdated : Instant.now(),
                Math.max(this.version, newer.version) + 1
        );
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
    public static String describeLayer(ProfileLayer layer) {
        return switch (layer) {
            case BasicProfile b -> "基础画像：粉丝" + b.followerCount()
                    + "，30日增长" + formatPercent(b.growthRate30d())
                    + "，周发文" + String.format("%.1f", b.postingFrequencyPerWeek()) + "篇";
            case ContentProfile c -> "内容画像：TOP作品" + c.topWorks().size()
                    + "篇，选题关键词" + c.topicKeywords().size() + "个";
            case PerformanceProfile p -> "表现画像：平均互动率" + formatPercent(p.avgEngagementRate())
                    + "，爆款率" + formatPercent(p.hitRate())
                    + "，趋势" + p.growthTrend().label();
            case ComparisonProfile cmp -> "对标画像：竞争烈度" + cmp.competitionIntensity()
                    + "/100，选题重叠" + formatPercent(cmp.topicOverlap())
                    + "，风格相似" + formatPercent(cmp.styleSimilarity());
        };
    }

    /**
     * 输出全部四层画像的概览摘要。
     *
     * @return 画像概览文本
     */
    public String overview() {
        StringBuilder sb = new StringBuilder();
        sb.append("竞品画像[").append(competitorAccountId).append("] 平台=").append(platform)
                .append(" 领域=").append(niche).append(" 版本=").append(version).append('\n');
        if (basic != null) {
            sb.append(" - ").append(describeLayer(basic)).append('\n');
        }
        if (content != null) {
            sb.append(" - ").append(describeLayer(content)).append('\n');
        }
        if (performance != null) {
            sb.append(" - ").append(describeLayer(performance)).append('\n');
        }
        if (comparison != null) {
            sb.append(" - ").append(describeLayer(comparison)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ──────────────────── 内部工具方法 ────────────────────

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback;
    }

    private static String formatPercent(double value) {
        return String.format("%.1f%%", value * 100);
    }
}
