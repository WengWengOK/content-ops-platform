package com.contentops.common.profile.competitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 竞品画像系统配置属性（P0 定向竞品监控）。
 *
 * <p>绑定到 application.yml 中的 {@code contentops.competitor-profile}：
 * <pre>
 * contentops:
 *   competitor-profile:
 *     default-monitor-frequency-hours: 24    # 监控频率默认值（小时）
 *     cache-ttl-minutes: 30                  # 画像缓存 TTL（分钟）
 *     cache-max-size: 500                    # 画像缓存最大条目数
 *     top-works-count: 10                    # 采集 TOP 作品数量
 *     hit-rate-threshold: 0.10               # 爆款阈值（TOP10%）
 *     follower-growth-spike-threshold: 0.20  # 粉丝增长率突变灵敏度
 *     posting-frequency-change-threshold: 0.30 # 发文频率变化灵敏度
 *     topic-direction-change-threshold: 0.40 # 内容方向转变灵敏度
 *     min-similarity-score: 0.50             # 相似度检索最低分
 *     style-vector-dim: 128                  # 风格向量维度
 * </pre>
 *
 * <p>该配置驱动 {@link CompetitorProfileService}、{@link CompetitorMonitorService}
 * 与 {@link CompetitorComparator} 的运行时行为，所有阈值均可通过配置热更新而无需改码。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.competitor-profile")
public class CompetitorProfileProperties {

    /** 新增监控任务的默认执行频率（小时），0 表示不自动调度、仅手动触发 */
    private int defaultMonitorFrequencyHours = 24;

    /** 竞品画像 Caffeine 缓存的写入后过期时间（分钟） */
    private int cacheTtlMinutes = 30;

    /** 竞品画像 Caffeine 缓存的最大条目数 */
    private int cacheMaxSize = 500;

    /** 构建内容画像时采集的 TOP 作品数量（对应「近30天TOP10」） */
    private int topWorksCount = 10;

    /** 爆款阈值 —— 互动率进入 TOP10% 的作品占比判定线（小数，0.10 表示 10%） */
    private double hitRateThreshold = 0.10;

    /** 粉丝增长率突变灵敏度 —— 30 日增长率绝对变化超过该值视为「粉丝异动」（小数） */
    private double followerGrowthSpikeThreshold = 0.20;

    /** 发文频率变化灵敏度 —— 周发文频率相对变化超过该值视为「发文频率变化」（小数） */
    private double postingFrequencyChangeThreshold = 0.30;

    /** 内容方向转变灵敏度 —— 选题关键词集合变化占比超过该值视为「内容方向转变」（小数） */
    private double topicDirectionChangeThreshold = 0.40;

    /** 风格相似度 / 竞品检索的最低相似度分值（0.0-1.0） */
    private double minSimilarityScore = 0.50;

    /** 风格特征向量的目标维度（用于特征哈希向量化） */
    private int styleVectorDim = 128;

    /** 竞争烈度满分基准（与 competitionIntensity 0-100 对齐） */
    private int competitionIntensityScale = 100;
}
