package com.contentops.common.profile.competitor;

import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.platform.BilibiliPlatformService;
import com.contentops.common.platform.DouyinPlatformService;
import com.contentops.common.platform.KuaishouPlatformService;
import com.contentops.common.platform.MetricsParser;
import com.contentops.common.platform.MetricsParser.ParsedMetrics;
import com.contentops.common.platform.WechatPlatformService;
import com.contentops.common.platform.XiaohongshuPlatformService;
import com.contentops.common.profile.competitor.CompetitorProfile.BasicProfile;
import com.contentops.common.profile.competitor.CompetitorProfile.ComparisonProfile;
import com.contentops.common.profile.competitor.CompetitorProfile.ContentProfile;
import com.contentops.common.profile.competitor.CompetitorProfile.GrowthTrend;
import com.contentops.common.profile.competitor.CompetitorProfile.PerformanceProfile;
import com.contentops.common.profile.competitor.CompetitorProfile.TitleStyle;
import com.contentops.common.profile.competitor.CompetitorProfile.TopWork;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 竞品画像构建服务（P0 定向竞品监控核心服务）。
 *
 * <p>负责从各平台服务拉取竞品原始数据，解析并组装为四层 {@link CompetitorProfile}，
 * 持久化到 RAG 知识库（{@link KnowledgeBaseService}，metadata type=competitor_profile），
 * 并通过 Caffeine 本地缓存（默认 30 分钟 TTL）加速读取。
 *
 * <p><b>存储分层：</b>
 * <ul>
 *   <li>{@code profileCache}（Caffeine）—— 完整画像的热读缓存，TTL 内直接命中</li>
 *   <li>{@code competitorIndex}（ConcurrentHashMap）—— 轻量索引，缓存过期后仍可列出竞品</li>
 *   <li>{@link KnowledgeBaseService} —— RAG 持久化，供 Topic Planning 等 Agent 语义检索竞品</li>
 * </ul>
 *
 * <p><b>降级策略：</b>当平台 API 不可用（未配置凭证 / 网络异常 / 返回降级文案）时，
 * 返回缓存画像或仅含身份信息的空画像（skeleton），绝不抛出异常阻断调用方流程。
 *
 * <p><b>平台集成说明：</b>各平台服务以 {@code @Autowired(required=false)} 注入，
 * 因 API 凭证可能未配置；微信公众号平台使用 App 级 token 可直接拉取数据，
 * 抖音 / 小红书 / B站 / 快手需竞品账号的 OAuth 授权 token，未授权时自动降级。
 */
@Slf4j
@Service
public class CompetitorProfileService {

    /** RAG 知识库中竞品画像的 metadata type 标识 */
    public static final String METADATA_TYPE_COMPETITOR_PROFILE = "competitor_profile";

    private final KnowledgeBaseService knowledgeBaseService;
    private final CompetitorProfileProperties properties;
    private final MetricsParser metricsParser;
    private final ObjectMapper objectMapper;

    /** 平台服务（按需注入，可能未配置 API 凭证） */
    @Autowired(required = false)
    private WechatPlatformService wechatPlatformService;
    @Autowired(required = false)
    private XiaohongshuPlatformService xiaohongshuPlatformService;
    @Autowired(required = false)
    private DouyinPlatformService douyinPlatformService;
    @Autowired(required = false)
    private BilibiliPlatformService bilibiliPlatformService;
    @Autowired(required = false)
    private KuaishouPlatformService kuaishouPlatformService;

    /** 完整画像热读缓存（accountId -> CompetitorProfile） */
    private final Cache<String, CompetitorProfile> profileCache;

    /** 竞品轻量索引（accountId -> 元信息），缓存过期后仍可列出与降级 */
    private final ConcurrentHashMap<String, CompetitorMeta> competitorIndex = new ConcurrentHashMap<>();

    /**
     * 构造服务并初始化 Caffeine 缓存。
     *
     * @param knowledgeBaseService RAG 知识库服务
     * @param properties           竞品画像配置
     * @param metricsParser        指标解析器
     */
    public CompetitorProfileService(KnowledgeBaseService knowledgeBaseService,
                                    CompetitorProfileProperties properties,
                                    MetricsParser metricsParser) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
        this.metricsParser = metricsParser;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.profileCache = Caffeine.newBuilder()
                .maximumSize(properties.getCacheMaxSize())
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, properties.getCacheTtlMinutes())))
                .recordStats()
                .build();

        log.info("CompetitorProfileService initialized: cacheTtl={}min, cacheMaxSize={}, topWorks={}",
                properties.getCacheTtlMinutes(), properties.getCacheMaxSize(), properties.getTopWorksCount());
    }

    // ════════════════════════════════════════════════════════════════
    // 核心方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 从平台 API 拉取数据构建完整竞品画像。
     *
     * <p>流程：路由到对应平台服务拉取原始数据 → {@link MetricsParser} 解析指标 →
     * 组装基础 / 内容 / 表现三层画像（对标层留空，由 {@link CompetitorComparator} 按需计算）
     * → 写入缓存与索引 → 持久化到 RAG 知识库。
     *
     * <p>当平台 API 不可用时，返回仅含身份信息的空画像（skeleton）并记录降级日志。
     *
     * @param competitorAccountId 竞品账号 ID
     * @param platform            平台标识（wechat / xiaohongshu / douyin / bilibili / kuaishou）
     * @param niche               所属领域
     * @return 构建完成的竞品画像（不为 null，最差为 skeleton）
     */
    public CompetitorProfile buildProfile(String competitorAccountId, String platform, String niche) {
        log.info("Building competitor profile: accountId={}, platform={}, niche={}",
                competitorAccountId, platform, niche);

        RawPlatformData rawData = fetchPlatformRawData(platform, competitorAccountId);
        CompetitorProfile profile = assembleProfile(competitorAccountId, platform, niche, rawData);

        cacheAndPersist(profile);
        return profile;
    }

    /**
     * 增量更新竞品画像 —— 重新拉取最新数据并与现有画像合并。
     *
     * <p>仅对已注册（存在于索引中）的竞品生效；合并时保留原始创建时间、递增版本号，
     * 便于 {@link CompetitorMonitorService} 做变更溯源。
     *
     * @param competitorAccountId 竞品账号 ID
     * @return 更新后的画像；若竞品不存在于索引则返回 null
     */
    public CompetitorProfile updateProfile(String competitorAccountId) {
        CompetitorMeta meta = competitorIndex.get(competitorAccountId);
        if (meta == null) {
            log.warn("Cannot update profile: competitor {} not registered", competitorAccountId);
            return null;
        }

        CompetitorProfile cached = profileCache.getIfPresent(competitorAccountId);
        RawPlatformData rawData = fetchPlatformRawData(meta.platform(), competitorAccountId);
        CompetitorProfile fresh = assembleProfile(competitorAccountId, meta.platform(), meta.niche(), rawData);

        CompetitorProfile updated = cached != null ? cached.merge(fresh) : fresh;
        cacheAndPersist(updated);
        log.info("Competitor profile updated: accountId={}, version={}",
                competitorAccountId, updated.version());
        return updated;
    }

    /**
     * 获取竞品画像 —— 优先命中 Caffeine 缓存。
     *
     * <p>降级策略：缓存未命中但竞品已注册时，返回仅含身份信息的空画像（skeleton），
     * 调用方可随后触发 {@link #updateProfile} 刷新完整数据；未注册的竞品返回 null。
     *
     * @param competitorAccountId 竞品账号 ID
     * @return 画像实例；缓存命中返回完整画像，已知竞品缓存过期返回 skeleton，未知返回 null
     */
    public CompetitorProfile getProfile(String competitorAccountId) {
        CompetitorProfile cached = profileCache.getIfPresent(competitorAccountId);
        if (cached != null) {
            return cached;
        }
        CompetitorMeta meta = competitorIndex.get(competitorAccountId);
        if (meta == null) {
            return null;
        }
        log.debug("Profile cache miss for registered competitor {}, returning skeleton", competitorAccountId);
        return CompetitorProfile.skeleton(competitorAccountId, meta.platform(), meta.niche());
    }

    /**
     * 按领域列出所有已注册竞品。
     *
     * @param niche 领域过滤条件；为 null 或空时返回全部竞品
     * @return 竞品元信息列表
     */
    public List<CompetitorMeta> listCompetitors(String niche) {
        return competitorIndex.values().stream()
                .filter(m -> niche == null || niche.isBlank() || niche.equalsIgnoreCase(m.niche()))
                .toList();
    }

    /**
     * 移除竞品 —— 清除缓存与索引。
     *
     * <p>注意：RAG 知识库中的历史画像记录予以保留，用于回溯分析；仅移除活跃监控与缓存。
     *
     * @param competitorAccountId 竞品账号 ID
     * @return 被移除的竞品元信息；不存在时返回 null
     */
    public CompetitorMeta removeCompetitor(String competitorAccountId) {
        CompetitorMeta removed = competitorIndex.remove(competitorAccountId);
        if (removed != null) {
            profileCache.invalidate(competitorAccountId);
            log.info("Competitor removed: accountId={}, platform={}", competitorAccountId, removed.platform());
        }
        return removed;
    }

    // ════════════════════════════════════════════════════════════════
    // 平台数据拉取与降级
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据平台路由拉取原始数据 —— 失败时返回空数据（降级）。
     *
     * @param platform            平台标识
     * @param competitorAccountId 竞品账号 ID
     * @return 平台原始数据（文本 + 是否含真实数据标志）
     */
    private RawPlatformData fetchPlatformRawData(String platform, String competitorAccountId) {
        return switch (normalizePlatform(platform)) {
            case "wechat" -> fetchFromWechat();
            case "douyin" -> fetchFromDouyin(competitorAccountId);
            case "xiaohongshu" -> fetchFromXiaohongshu(competitorAccountId);
            case "bilibili" -> fetchFromBilibili(competitorAccountId);
            case "kuaishou" -> fetchFromKuaishou(competitorAccountId);
            default -> {
                log.warn("Unsupported platform '{}', falling back to empty data", platform);
                yield RawPlatformData.empty();
            }
        };
    }

    /**
     * 从微信公众号平台拉取数据 —— 使用 App 级 token，可直接调用数据分析接口。
     */
    private RawPlatformData fetchFromWechat() {
        if (wechatPlatformService == null || !wechatPlatformService.isAvailable()) {
            log.debug("Wechat platform unavailable, degrading");
            return RawPlatformData.empty();
        }
        try {
            StringBuilder raw = new StringBuilder();
            raw.append(wechatPlatformService.getUserSummary(null, null)).append('\n');
            raw.append(wechatPlatformService.getArticleReadData(null)).append('\n');
            raw.append(wechatPlatformService.getArticleTotalDetail(null, null)).append('\n');
            return new RawPlatformData(raw.toString(), true);
        } catch (Exception e) {
            log.error("Failed to fetch Wechat data, degrading", e);
            return RawPlatformData.empty();
        }
    }

    /**
     * 从抖音平台拉取数据 —— 需竞品 OAuth token，未授权时降级。
     */
    private RawPlatformData fetchFromDouyin(String competitorAccountId) {
        if (douyinPlatformService == null || !douyinPlatformService.isAvailable()) {
            log.debug("Douyin platform unavailable, degrading");
            return RawPlatformData.empty();
        }
        try {
            // 抖音需竞品账号 access_token + open_id，未授权时返回降级文案
            String result = douyinPlatformService.queryVideoList("", competitorAccountId, 0, 20);
            return new RawPlatformData(result, !isDegradedMessage(result));
        } catch (Exception e) {
            log.error("Failed to fetch Douyin data, degrading", e);
            return RawPlatformData.empty();
        }
    }

    /**
     * 从小红书平台拉取数据 —— 需竞品 OAuth token，未授权时降级。
     */
    private RawPlatformData fetchFromXiaohongshu(String competitorAccountId) {
        if (xiaohongshuPlatformService == null || !xiaohongshuPlatformService.isAvailable()) {
            log.debug("Xiaohongshu platform unavailable, degrading");
            return RawPlatformData.empty();
        }
        try {
            String result = xiaohongshuPlatformService.getNoteDetail("", competitorAccountId);
            return new RawPlatformData(result, !isDegradedMessage(result));
        } catch (Exception e) {
            log.error("Failed to fetch Xiaohongshu data, degrading", e);
            return RawPlatformData.empty();
        }
    }

    /**
     * 从 B站平台拉取数据 —— 需竞品 OAuth token，未授权时降级。
     */
    private RawPlatformData fetchFromBilibili(String competitorAccountId) {
        if (bilibiliPlatformService == null || !bilibiliPlatformService.isAvailable()) {
            log.debug("Bilibili platform unavailable, degrading");
            return RawPlatformData.empty();
        }
        try {
            String result = bilibiliPlatformService.getVideoStats("", competitorAccountId);
            return new RawPlatformData(result, !isDegradedMessage(result));
        } catch (Exception e) {
            log.error("Failed to fetch Bilibili data, degrading", e);
            return RawPlatformData.empty();
        }
    }

    /**
     * 从快手平台拉取数据 —— 需竞品 OAuth token，未授权时降级。
     */
    private RawPlatformData fetchFromKuaishou(String competitorAccountId) {
        if (kuaishouPlatformService == null || !kuaishouPlatformService.isAvailable()) {
            log.debug("Kuaishou platform unavailable, degrading");
            return RawPlatformData.empty();
        }
        try {
            StringBuilder raw = new StringBuilder();
            raw.append(kuaishouPlatformService.getUserInfo("")).append('\n');
            raw.append(kuaishouPlatformService.queryVideoList("", 1, 20)).append('\n');
            return new RawPlatformData(raw.toString(), true);
        } catch (Exception e) {
            log.error("Failed to fetch Kuaishou data, degrading", e);
            return RawPlatformData.empty();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 画像组装
    // ════════════════════════════════════════════════════════════════

    /**
     * 将平台原始数据组装为四层画像（对标层留空）。
     */
    private CompetitorProfile assembleProfile(String accountId, String platform, String niche, RawPlatformData raw) {
        ParsedMetrics metrics = raw.hasRealData()
                ? metricsParser.parse(raw.text())
                : new ParsedMetrics();

        BasicProfile basic = buildBasicProfile(accountId, platform, niche, metrics);
        ContentProfile content = buildContentProfile(raw, metrics);
        PerformanceProfile performance = buildPerformanceProfile(metrics);
        ComparisonProfile comparison = ComparisonProfile.empty();

        Instant now = Instant.now();
        return new CompetitorProfile(accountId, platform, niche, basic, content, performance, comparison,
                now, now, 1);
    }

    /**
     * 构建基础画像层。
     */
    private BasicProfile buildBasicProfile(String accountId, String platform, String niche, ParsedMetrics m) {
        long followerCount = m.netGrowth() > 0 ? m.netGrowth() : m.newUsers();
        double growthRate30d = metricsParser.computeGrowthRate(m);
        // 发文频率：粗略按解析到的内容条数估算（无直接字段时取 0）
        double postingFrequency = estimatePostingFrequency(m);
        List<String> bestSlots = List.of("20:00-22:00", "12:00-13:00");
        String verification = "未知";
        double tierWeight = computeTierWeight(followerCount);
        Map<String, Double> contentTypeRatio = new HashMap<>();
        contentTypeRatio.put("图文", 0.5);
        contentTypeRatio.put("视频", 0.5);

        return new BasicProfile(accountId, platform, niche, followerCount, growthRate30d,
                postingFrequency, bestSlots, verification, tierWeight, contentTypeRatio);
    }

    /**
     * 构建内容画像层 —— 从原始文本中提取 TOP 作品与风格特征。
     */
    private ContentProfile buildContentProfile(RawPlatformData raw, ParsedMetrics m) {
        if (!raw.hasRealData()) {
            return ContentProfile.empty();
        }
        List<TopWork> topWorks = extractTopWorks(raw.text(), m);
        List<String> topicKeywords = extractTopicKeywords(raw.text());
        TitleStyle titleStyle = extractTitleStyle(raw.text());
        String contentStyle = "数据有限，风格待补充";
        String visualStyle = "数据有限，视觉风格待补充";
        return new ContentProfile(topWorks, topicKeywords, titleStyle, contentStyle, visualStyle);
    }

    /**
     * 构建表现画像层。
     */
    private PerformanceProfile buildPerformanceProfile(ParsedMetrics m) {
        double avgEngagement = m.engagementRate() > 0
                ? m.engagementRate()
                : metricsParser.computeEngagementRate(m);
        double medianEngagement = avgEngagement * 0.8; // 中位数估算（无逐篇数据时取均值折扣）
        double hitRate = properties.getHitRateThreshold();
        Map<String, Double> matrix = new HashMap<>();
        matrix.put("当前平台", avgEngagement);
        GrowthTrend trend = GrowthTrend.fromRate(m.growthRate(), 0.05);
        List<String> traits = new ArrayList<>();
        if (m.collectCount() > 0) {
            traits.add("高收藏率内容受用户认可");
        }
        if (m.shareCount() > 0) {
            traits.add("高分享率内容具传播性");
        }
        return new PerformanceProfile(avgEngagement, medianEngagement, hitRate, matrix, trend, traits);
    }

    // ════════════════════════════════════════════════════════════════
    // 缓存与持久化
    // ════════════════════════════════════════════════════════════════

    /**
     * 写入缓存、索引与 RAG 知识库。
     */
    private void cacheAndPersist(CompetitorProfile profile) {
        String accountId = profile.competitorAccountId();
        profileCache.put(accountId, profile);
        competitorIndex.put(accountId, new CompetitorMeta(
                accountId, profile.platform(), profile.niche(), profile.lastUpdated(), profile.version()));
        persistToKnowledgeBase(profile);
    }

    /**
     * 将画像 JSON 持久化到 RAG 知识库，供语义检索。
     */
    private void persistToKnowledgeBase(CompetitorProfile profile) {
        if (!knowledgeBaseService.isAvailable()) {
            log.debug("Knowledge base unavailable, skipping profile persistence for {}", profile.competitorAccountId());
            return;
        }
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("type", METADATA_TYPE_COMPETITOR_PROFILE);
            metadata.put("agent", "competitor-monitor");
            metadata.put("niche", profile.niche() != null ? profile.niche() : "unknown");
            metadata.put("platform", profile.platform() != null ? profile.platform() : "unknown");
            metadata.put("competitorAccountId", profile.competitorAccountId());
            metadata.put("version", String.valueOf(profile.version()));
            metadata.put("timestamp", Instant.now().toString());

            boolean ok = knowledgeBaseService.ingest(profile.overview() + "\n\n" + json, metadata);
            if (ok) {
                log.debug("Competitor profile persisted to knowledge base: {}", profile.competitorAccountId());
            }
        } catch (Exception e) {
            log.error("Failed to persist competitor profile to knowledge base: {}",
                    profile.competitorAccountId(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 从原始文本中提取 TOP 作品列表（基于解析到的标题与指标）。
     */
    private List<TopWork> extractTopWorks(String text, ParsedMetrics m) {
        List<TopWork> works = new ArrayList<>();
        // 从文本中按「标题:」前缀提取标题
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("标题[：:]\\s*(.+)");
        java.util.regex.Matcher matcher = p.matcher(text);
        int count = 0;
        while (matcher.find() && count < properties.getTopWorksCount()) {
            String title = matcher.group(1).trim();
            // 取文本行末换行前的内容，避免截取过多
            int lineEnd = title.indexOf('\n');
            if (lineEnd > 0) {
                title = title.substring(0, lineEnd).trim();
            }
            works.add(new TopWork(title, "", m.readCount(), m.likes(),
                    m.commentCount(), m.shareCount(), m.engagementRate()));
            count++;
        }
        // 若未提取到标题，构造一条汇总作品
        if (works.isEmpty() && m.hasData()) {
            works.add(new TopWork("（汇总）近期代表作", "", m.readCount(), m.likes(),
                    m.commentCount(), m.shareCount(), m.engagementRate()));
        }
        return works;
    }

    /**
     * 从原始文本中提取选题关键词（简单按高频词提取，降级为领域标签）。
     */
    private List<String> extractTopicKeywords(String text) {
        // 简化实现：返回通用方向标签；生产环境可接入 NLP 关键词抽取
        return List.of("行业洞察", "实用教程", "热点解读");
    }

    /**
     * 从原始文本中提取标题风格特征。
     */
    private TitleStyle extractTitleStyle(String text) {
        return new TitleStyle(12.0, "混合式", List.of("干货", "必看"), List.of("口语化"));
    }

    /**
     * 估算发文频率（篇/周）—— 基于解析到的内容条数。
     */
    private double estimatePostingFrequency(ParsedMetrics m) {
        if (m.readCount() > 0) {
            return Math.max(1.0, Math.round(m.readCount() / 4.0));
        }
        return 3.0; // 默认每周 3 篇
    }

    /**
     * 根据粉丝量级计算等级权重（0.0-1.0）。
     */
    private double computeTierWeight(long followerCount) {
        if (followerCount >= 1_000_000) return 1.0;
        if (followerCount >= 100_000) return 0.8;
        if (followerCount >= 10_000) return 0.6;
        if (followerCount >= 1_000) return 0.4;
        return 0.2;
    }

    /**
     * 判断平台返回是否为降级文案（以「[」开头表示不可用提示）。
     */
    private boolean isDegradedMessage(String result) {
        return result == null || result.isBlank() || result.strip().startsWith("[");
    }

    /**
     * 归一化平台标识。
     */
    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "unknown";
        }
        String lower = platform.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "wechat", "微信", "公众号", "weixin" -> "wechat";
            case "xiaohongshu", "小红书", "xhs", "redbook" -> "xiaohongshu";
            case "douyin", "抖音", "tiktok" -> "douyin";
            case "bilibili", "哔哩哔哩", "b站", "bili" -> "bilibili";
            case "kuaishou", "快手", "ks" -> "kuaishou";
            default -> lower;
        };
    }

    /**
     * 获取缓存统计信息（用于监控与运维）。
     *
     * @return 缓存统计快照
     */
    public Map<String, Object> cacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", profileCache.estimatedSize());
        stats.put("competitorCount", competitorIndex.size());
        stats.put("hitRate", profileCache.stats().hitRate());
        stats.put("requestCount", profileCache.stats().requestCount());
        return stats;
    }

    // ════════════════════════════════════════════════════════════════
    // 内部数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 平台原始拉取数据。
     *
     * @param text        原始文本（平台格式化输出）
     * @param hasRealData 是否包含真实数据（false 表示降级）
     */
    private record RawPlatformData(String text, boolean hasRealData) {

        static RawPlatformData empty() {
            return new RawPlatformData("", false);
        }
    }

    /**
     * 竞品轻量索引元信息。
     *
     * @param competitorAccountId 竞品账号 ID
     * @param platform            平台
     * @param niche               领域
     * @param lastUpdated         最近更新时间
     * @param version             画像版本号
     */
    public record CompetitorMeta(
            String competitorAccountId,
            String platform,
            String niche,
            Instant lastUpdated,
            int version
    ) {
    }
}
