package com.contentops.common.profile.audience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 受众画像系统配置属性类（P1 用户画像扩展系统）。
 *
 * <p>绑定到 {@code application.yml} 中的 {@code contentops.audience-profile}：
 * <pre>
 * contentops:
 *   audience-profile:
 *     cache-ttl-minutes: 60              # 画像缓存 TTL（分钟），默认 1 小时
 *     cache-max-size: 500                # 缓存最大条目数
 *     region-stat-count: 5               # 地域分布统计数量（TOP N）
 *     time-slot-granularity: 2           # 活跃时段粒度（小时，如 2 表示 2 小时一个时段）
 *     max-topic-keywords: 10             # 选题关键词最大数量（TOP N）
 *     max-tag-preferences: 10            # 内容偏好标签最大数量
 *     default-platform: wechat           # 默认拉取平台
 *     rapid-growth-threshold: 0.10       # 快速增长判定阈值（30日增长率超过 10% 视为快速增长）
 *     decline-threshold: -0.05           # 粉丝流失判定阈值（30日增长率低于 -5% 视为流失）
 *     enable-content-profile: true       # 是否启用内容画像（关闭后仅构建受众画像）
 * </pre>
 *
 * <p>该配置驱动 {@link AudienceProfileService}（缓存与平台拉取策略）、
 * {@link ProfileEnricher}（摘要生成参数）等组件的行为，
 * 全部参数均可通过配置文件覆盖，无需改代码。
 *
 * @see AudienceProfileService
 * @see ProfileEnricher
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.audience-profile")
public class AudienceProfileProperties {

    /** 画像缓存 TTL（分钟），默认 60 分钟（1 小时）。 */
    private long cacheTtlMinutes = 60;

    /** 画像缓存最大条目数。 */
    private int cacheMaxSize = 500;

    /** 地域分布统计数量（TOP N），默认 TOP5。 */
    private int regionStatCount = 5;

    /**
     * 活跃时段粒度（小时），默认 2 小时一个时段。
     * <p>如 2 表示时段为 {@code "00:00-02:00", "02:00-04:00", ...}；
     * 1 表示按小时粒度。
     */
    private int timeSlotGranularity = 2;

    /** 选题关键词最大数量（TOP N），默认 TOP10。 */
    private int maxTopicKeywords = 10;

    /** 内容偏好标签最大数量，默认 10 个。 */
    private int maxTagPreferences = 10;

    /**
     * 默认拉取平台（当未指定平台时使用）。
     * <p>可选值：wechat / xiaohongshu / douyin / bilibili / kuaishou。
     */
    private String defaultPlatform = "wechat";

    /** 快速增长判定阈值（30 日增长率超过该值视为快速增长），默认 0.10（10%）。 */
    private double rapidGrowthThreshold = 0.10;

    /** 粉丝流失判定阈值（30 日增长率低于该负值视为流失），默认 -0.05（-5%）。 */
    private double declineThreshold = -0.05;

    /** 是否启用内容画像（关闭后仅构建受众画像，跳过 ContentProfile 构建）。 */
    private boolean enableContentProfile = true;

    /**
     * 默认增长渠道分布（当平台未返回渠道数据时使用）。
     * <p>key 为渠道名，value 为占比。
     */
    private List<String> defaultGrowthChannels = List.of("搜索", "推荐", "分享", "其他");
}
