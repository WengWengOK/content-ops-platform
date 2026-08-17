package com.contentops.common.profile.audience;

import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.platform.BilibiliPlatformService;
import com.contentops.common.platform.DouyinPlatformService;
import com.contentops.common.platform.KuaishouPlatformService;
import com.contentops.common.platform.MetricsParser;
import com.contentops.common.platform.MetricsParser.ParsedMetrics;
import com.contentops.common.platform.WechatPlatformService;
import com.contentops.common.platform.XiaohongshuPlatformService;
import com.contentops.common.profile.audience.AudienceProfile.AgeDistribution;
import com.contentops.common.profile.audience.AudienceProfile.BehaviorProfile;
import com.contentops.common.profile.audience.AudienceProfile.DemographicProfile;
import com.contentops.common.profile.audience.AudienceProfile.GenderDistribution;
import com.contentops.common.profile.audience.AudienceProfile.GrowthProfile;
import com.contentops.common.profile.audience.AudienceProfile.GrowthTrend;
import com.contentops.common.profile.audience.AudienceProfile.InteractionTendency;
import com.contentops.common.profile.audience.AudienceProfile.RegionStat;
import com.contentops.common.profile.audience.AudienceProfile.TagPreference;
import com.contentops.common.profile.audience.AudienceProfile.TimeSlotActivity;
import com.contentops.common.profile.audience.ContentProfile.ContentType;
import com.contentops.common.profile.audience.ContentProfile.PerformanceHistory;
import com.contentops.common.profile.audience.ContentProfile.PlatformFit;
import com.contentops.common.profile.audience.ContentProfile.TopicDistribution;
import com.contentops.common.profile.audience.ContentProfile.TopicKeyword;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受众画像构建服务（P1 用户画像扩展系统核心服务）。
 *
 * <p>负责从各平台服务拉取粉丝数据，通过 {@link MetricsParser} 解析指标，组装为多维结构化的
 * {@link AudienceProfile} 与 {@link ContentProfile}，持久化到 RAG 知识库
 * （{@link KnowledgeBaseService}，metadata type="audience_profile"），并通过 Caffeine
 * 本地缓存（默认 1 小时 TTL）加速读取。
 *
 * <h3>核心方法</h3>
 * <ul>
 *   <li>{@link #buildProfile} —— 从平台 API 拉取粉丝数据构建受众画像</li>
 *   <li>{@link #buildProfileFromMetrics} —— 从 MetricsParser 解析的文本构建画像</li>
 *   <li>{@link #updateProfile} —— 更新画像</li>
 *   <li>{@link #getProfile} —— 获取画像（Caffeine 缓存 1h）</li>
 *   <li>{@link #mergeWithBase} —— 将结构化受众画像与基础 AccountProfile 合并</li>
 * </ul>
 *
 * <h3>存储分层</h3>
 * <ul>
 *   <li>{@code audienceCache}（Caffeine）—— 受众画像热读缓存，TTL 内直接命中</li>
 *   <li>{@code contentCache}（Caffeine）—— 内容画像热读缓存</li>
 *   <li>{@code profileIndex}（ConcurrentHashMap）—— 轻量索引，缓存过期后仍可列出账号</li>
 *   <li>{@link KnowledgeBaseService} —— RAG 持久化，供各 Agent 语义检索画像</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <p>当平台 API 不可用（未配置凭证 / 网络异常 / 返回降级文案）时，
 * 返回空画像（{@link AudienceProfile#empty}），绝不抛出异常阻断调用方流程。
 *
 * <h3>平台集成说明</h3>
 * <p>各平台服务以 {@code @Autowired(required=false)} 注入，因 API 凭证可能未配置；
 * 微信公众号平台使用 App 级 token 可直接拉取数据，抖音 / 小红书 / B站 / 快手需 OAuth 授权 token。
 *
 * @see AudienceProfile
 * @see ContentProfile
 * @see MetricsParser
 * @see KnowledgeBaseService
 */
@Slf4j
@Service
public class AudienceProfileService {

    /** RAG 知识库中受众画像的 metadata type 标识 */
    public static final String METADATA_TYPE_AUDIENCE_PROFILE = "audience_profile";
    /** RAG 知识库中内容画像的 metadata type 标识 */
    public static final String METADATA_TYPE_CONTENT_PROFILE = "content_profile";

    // ════════════════ 预编译正则与分类关键词 ════════════════

    /** 地域分布标签正则，匹配「地域:」「省份分布:」「地区:」「区域分布:」等前缀。 */
    private static final Pattern REGION_PATTERN =
            Pattern.compile("(?:地域|省份|地区|区域)分布?\\s*[：:]?\\s*(.+)");

    /** 地域条目正则，从捕获组中解析「广东(18%)」「广东 18%」「北京12.5%」等条目。 */
    private static final Pattern REGION_ENTRY_PATTERN =
            Pattern.compile("([\\u4e00-\\u9fa5]{2,})\\s*[（(]?\\s*(\\d+(?:\\.\\d+)?)\\s*%[）)]?");

    /** 完整日期时间正则，用于提取发布小时，如「2024-01-15 21:30:00」或「2024/01/15T21:30」。 */
    private static final Pattern DATETIME_PATTERN =
            Pattern.compile("(\\d{4})[-/](\\d{2})[-/](\\d{2})[ T](\\d{2}):(\\d{2})");

    /** 创建时间标签正则，匹配「创建时间: xxx」或「create_time: xxx」。 */
    private static final Pattern CREATE_TIME_PATTERN =
            Pattern.compile("(?:创建时间|create_time)\\s*[：:]\\s*(\\S+)");

    /** 纯数字（9位以上）判断，用于识别 Unix 时间戳。 */
    private static final Pattern EPOCH_PATTERN = Pattern.compile("^\\d{9,}$");

    /** 内容块标题行正则（支持全角/半角冒号），如「- 标题: xxx」或「- 标题：xxx」。 */
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("(?m)^-\\s*标题\\s*[：:]\\s*(.+)");

    /** 中国时区，用于把 Unix 时间戳转换为本地小时。 */
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    /** 内容类型 → 关键词数组（保持插入顺序，决定分类匹配优先级），与 MetricsCalculator 保持一致。 */
    private static final LinkedHashMap<String, String[]> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        CATEGORY_KEYWORDS.put("干货教程", new String[]{"教程", "指南", "方法", "技巧", "如何", "步骤", "攻略", "干货"});
        CATEGORY_KEYWORDS.put("个人故事", new String[]{"经历", "故事", "我的", "回忆", "成长", "转变", "真实"});
        CATEGORY_KEYWORDS.put("热点解读", new String[]{"热点", "最新", "刚刚", "突发", "解读", "分析", "事件"});
        CATEGORY_KEYWORDS.put("清单盘点", new String[]{"盘点", "清单", "推荐", "合集", "TOP", "排名", "精选"});
        CATEGORY_KEYWORDS.put("观点输出", new String[]{"观点", "认为", "应该", "为什么", "思考", "看法", "反思"});
    }

    private final KnowledgeBaseService knowledgeBaseService;
    private final AudienceProfileProperties properties;
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

    /** 受众画像热读缓存（accountId -> AudienceProfile） */
    private final Cache<String, AudienceProfile> audienceCache;

    /** 内容画像热读缓存（accountId -> ContentProfile） */
    private final Cache<String, ContentProfile> contentCache;

    /** 账号轻量索引（accountId -> 元信息），缓存过期后仍可列出与降级 */
    private final ConcurrentHashMap<String, AccountMeta> profileIndex = new ConcurrentHashMap<>();

    /**
     * 构造服务并初始化 Caffeine 缓存。
     *
     * @param knowledgeBaseService RAG 知识库服务
     * @param properties           受众画像配置
     * @param metricsParser        指标解析器
     */
    public AudienceProfileService(KnowledgeBaseService knowledgeBaseService,
                                  AudienceProfileProperties properties,
                                  MetricsParser metricsParser) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
        this.metricsParser = metricsParser;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Duration ttl = Duration.ofMinutes(Math.max(1, properties.getCacheTtlMinutes()));
        this.audienceCache = Caffeine.newBuilder()
                .maximumSize(properties.getCacheMaxSize())
                .expireAfterWrite(ttl)
                .recordStats()
                .build();
        this.contentCache = Caffeine.newBuilder()
                .maximumSize(properties.getCacheMaxSize())
                .expireAfterWrite(ttl)
                .recordStats()
                .build();

        log.info("AudienceProfileService initialized: cacheTtl={}min, cacheMaxSize={}, contentProfile={}",
                properties.getCacheTtlMinutes(), properties.getCacheMaxSize(), properties.isEnableContentProfile());
    }

    // ════════════════════════════════════════════════════════════════
    // 核心方法：受众画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 从平台 API 拉取粉丝数据构建受众画像。
     *
     * <p>流程：路由到对应平台服务拉取原始数据 → {@link MetricsParser} 解析指标 →
     * 组装人口属性 / 行为偏好 / 增长态势三层画像 → 写入缓存与索引 → 持久化到 RAG 知识库。
     *
     * <p>当平台 API 不可用时，返回空画像（{@link AudienceProfile#empty}）并记录降级日志。
     *
     * @param accountId 账号 ID
     * @return 构建完成的受众画像（不为 null，最差为空画像）
     */
    public AudienceProfile buildProfile(String accountId) {
        return buildProfile(accountId, properties.getDefaultPlatform());
    }

    /**
     * 从指定平台 API 拉取粉丝数据构建受众画像。
     *
     * @param accountId 账号 ID
     * @param platform  平台标识（wechat / xiaohongshu / douyin / bilibili / kuaishou）
     * @return 构建完成的受众画像（不为 null，最差为空画像）
     */
    public AudienceProfile buildProfile(String accountId, String platform) {
        requireAccountId(accountId);
        log.info("[Audience] 构建受众画像: accountId={}, platform={}", accountId, platform);

        RawPlatformData rawData = fetchPlatformRawData(platform, accountId);
        AudienceProfile profile = assembleAudienceProfile(accountId, rawData);

        cacheAndPersistAudience(profile, platform);
        return profile;
    }

    /**
     * 从 MetricsParser 解析的文本构建画像。
     *
     * <p>当无法直接调用平台 API（如离线分析、手工导入数据）时，可传入平台导出的原始文本，
     * 由 {@link MetricsParser} 解析后构建画像。
     *
     * @param accountId     账号 ID
     * @param rawMetricsText 原始指标文本（平台格式化输出）
     * @return 构建完成的受众画像（不为 null，最差为空画像）
     */
    public AudienceProfile buildProfileFromMetrics(String accountId, String rawMetricsText) {
        requireAccountId(accountId);
        log.info("[Audience] 从指标文本构建画像: accountId={}, textLength={}",
                accountId, rawMetricsText == null ? 0 : rawMetricsText.length());

        RawPlatformData rawData = new RawPlatformData(
                rawMetricsText != null ? rawMetricsText : "",
                rawMetricsText != null && !rawMetricsText.isBlank());
        AudienceProfile profile = assembleAudienceProfile(accountId, rawData);

        cacheAndPersistAudience(profile, properties.getDefaultPlatform());
        return profile;
    }

    /**
     * 更新画像 —— 将新画像写入缓存与索引，并持久化到 RAG 知识库。
     *
     * @param accountId 账号 ID
     * @param profile   新的受众画像
     * @return 更新后的画像
     */
    public AudienceProfile updateProfile(String accountId, AudienceProfile profile) {
        requireAccountId(accountId);
        AudienceProfile updated = profile.withAccountId(accountId);
        cacheAndPersistAudience(updated, properties.getDefaultPlatform());
        log.info("[Audience] 更新受众画像: accountId={}, hasData={}", accountId, updated.hasData());
        return updated;
    }

    /**
     * 获取受众画像 —— 优先命中 Caffeine 缓存。
     *
     * <p>降级策略：缓存未命中但账号已注册时，返回空画像；
     * 未注册的账号返回 null。
     *
     * @param accountId 账号 ID
     * @return 画像实例；缓存命中返回完整画像，已知账号缓存过期返回空画像，未知返回 null
     */
    public AudienceProfile getProfile(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return null;
        }
        AudienceProfile cached = audienceCache.getIfPresent(accountId);
        if (cached != null) {
            return cached;
        }
        AccountMeta meta = profileIndex.get(accountId);
        if (meta == null) {
            return null;
        }
        log.debug("[Audience] 缓存未命中，返回空画像: accountId={}", accountId);
        return AudienceProfile.empty(accountId);
    }

    // ════════════════════════════════════════════════════════════════
    // 核心方法：内容画像
    // ════════════════════════════════════════════════════════════════

    /**
     * 从平台 API 拉取数据构建内容画像。
     *
     * <p>当 {@link AudienceProfileProperties#isEnableContentProfile()} 为 false 时，
     * 直接返回空内容画像。
     *
     * @param accountId 账号 ID
     * @return 构建完成的内容画像（不为 null，最差为空画像）
     */
    public ContentProfile buildContentProfile(String accountId) {
        return buildContentProfile(accountId, properties.getDefaultPlatform());
    }

    /**
     * 从指定平台 API 拉取数据构建内容画像。
     *
     * @param accountId 账号 ID
     * @param platform  平台标识
     * @return 构建完成的内容画像
     */
    public ContentProfile buildContentProfile(String accountId, String platform) {
        requireAccountId(accountId);
        if (!properties.isEnableContentProfile()) {
            log.debug("[Audience] 内容画像已禁用，返回空画像: accountId={}", accountId);
            return ContentProfile.empty(accountId);
        }
        log.info("[Audience] 构建内容画像: accountId={}, platform={}", accountId, platform);

        RawPlatformData rawData = fetchPlatformRawData(platform, accountId);
        ContentProfile profile = assembleContentProfile(accountId, rawData);

        cacheAndPersistContent(profile);
        return profile;
    }

    /**
     * 从指标文本构建内容画像。
     *
     * @param accountId     账号 ID
     * @param rawMetricsText 原始指标文本
     * @return 构建完成的内容画像
     */
    public ContentProfile buildContentProfileFromMetrics(String accountId, String rawMetricsText) {
        requireAccountId(accountId);
        if (!properties.isEnableContentProfile()) {
            return ContentProfile.empty(accountId);
        }
        RawPlatformData rawData = new RawPlatformData(
                rawMetricsText != null ? rawMetricsText : "",
                rawMetricsText != null && !rawMetricsText.isBlank());
        ContentProfile profile = assembleContentProfile(accountId, rawData);
        cacheAndPersistContent(profile);
        return profile;
    }

    /**
     * 获取内容画像 —— 优先命中 Caffeine 缓存。
     *
     * @param accountId 账号 ID
     * @return 内容画像实例；不存在时返回 null
     */
    public ContentProfile getContentProfile(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return null;
        }
        ContentProfile cached = contentCache.getIfPresent(accountId);
        if (cached != null) {
            return cached;
        }
        return null;
    }

    /**
     * 更新内容画像。
     *
     * @param accountId 账号 ID
     * @param profile   新的内容画像
     * @return 更新后的内容画像
     */
    public ContentProfile updateContentProfile(String accountId, ContentProfile profile) {
        requireAccountId(accountId);
        ContentProfile updated = profile.withAccountId(accountId);
        cacheAndPersistContent(updated);
        log.info("[Audience] 更新内容画像: accountId={}, hasData={}", accountId, updated.hasData());
        return updated;
    }

    // ════════════════════════════════════════════════════════════════
    // 合并方法：结构化画像与基础 AccountProfile
    // ════════════════════════════════════════════════════════════════

    /**
     * 将结构化受众画像与基础 AccountProfile 合并。
     *
     * <p>不修改原始 AccountProfile 的字段结构（保持向后兼容），而是将受众画像的关键信息
     * 以可读文本形式注入到 {@code targetAudience} 字段中，使现有 Agent 能感知结构化画像。
     *
     * <p>合并策略：
     * <ul>
     *   <li>若受众画像无数据，原样返回基础画像</li>
     *   <li>若有数据，将粉丝量级、性别分布、地域 TOP3、活跃时段、内容偏好追加到 targetAudience</li>
     * </ul>
     *
     * @param base    基础账号画像（不可为 null）
     * @param audience 结构化受众画像（可为 null）
     * @return 合并后的基础账号画像（新对象，不修改入参）
     */
    public AccountProfile mergeWithBase(AccountProfile base, AudienceProfile audience) {
        if (base == null) {
            throw new IllegalArgumentException("base AccountProfile 不能为空");
        }
        if (audience == null || !audience.hasData()) {
            return base;
        }

        String enrichedTargetAudience = buildEnrichedTargetAudience(base.getTargetAudience(), audience);
        return AccountProfile.builder()
                .accountId(base.getAccountId())
                .accountName(base.getAccountName())
                .niche(base.getNiche())
                .targetAudience(enrichedTargetAudience)
                .tone(base.getTone())
                .platforms(base.getPlatforms())
                .personalExperience(base.getPersonalExperience())
                .build();
    }

    // ════════════════════════════════════════════════════════════════
    // 平台数据拉取与降级
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据平台路由拉取原始数据 —— 失败时返回空数据（降级）。
     *
     * @param platform  平台标识
     * @param accountId 账号 ID
     * @return 平台原始数据（文本 + 是否含真实数据标志）
     */
    private RawPlatformData fetchPlatformRawData(String platform, String accountId) {
        return switch (normalizePlatform(platform)) {
            case "wechat" -> fetchFromWechat();
            case "douyin" -> fetchFromDouyin(accountId);
            case "xiaohongshu" -> fetchFromXiaohongshu(accountId);
            case "bilibili" -> fetchFromBilibili(accountId);
            case "kuaishou" -> fetchFromKuaishou();
            default -> {
                log.warn("[Audience] 不支持的平台 '{}'，降级返回空数据", platform);
                yield RawPlatformData.empty();
            }
        };
    }

    /**
     * 从微信公众号平台拉取数据。
     */
    private RawPlatformData fetchFromWechat() {
        if (wechatPlatformService == null || !wechatPlatformService.isAvailable()) {
            log.debug("[Audience] 微信平台不可用，降级");
            return RawPlatformData.empty();
        }
        try {
            StringBuilder raw = new StringBuilder();
            raw.append(wechatPlatformService.getUserSummary(null, null)).append('\n');
            raw.append(wechatPlatformService.getArticleReadData(null)).append('\n');
            raw.append(wechatPlatformService.getArticleTotalDetail(null, null)).append('\n');
            return new RawPlatformData(raw.toString(), true);
        } catch (Exception e) {
            log.error("[Audience] 微信数据拉取失败，降级", e);
            return RawPlatformData.empty();
        }
    }

    /**
     * 从抖音平台拉取数据 —— 需 OAuth token。
     */
    private RawPlatformData fetchFromDouyin(String accountId) {
        if (douyinPlatformService == null || !douyinPlatformService.isAvailable()) {
            log.debug("[Audience] 抖音平台不可用，降级");
            return RawPlatformData.empty();
        }
        try {
            String result = douyinPlatformService.queryVideoList("", accountId, 0, 20);
            return new RawPlatformData(result, !isDegradedMessage(result));
        } catch (Exception e) {
            log.error("[Audience] 抖音数据拉取失败，降级", e);
            return RawPlatformData.empty();
        }
    }

    /**
     * 从小红书平台拉取数据 —— 需 OAuth token。
     */
    private RawPlatformData fetchFromXiaohongshu(String accountId) {
        if (xiaohongshuPlatformService == null || !xiaohongshuPlatformService.isAvailable()) {
            log.debug("[Audience] 小红书平台不可用，降级");
            return RawPlatformData.empty();
        }
        try {
            String result = xiaohongshuPlatformService.getNoteDetail("", accountId);
            return new RawPlatformData(result, !isDegradedMessage(result));
        } catch (Exception e) {
            log.error("[Audience] 小红书数据拉取失败，降级", e);
            return RawPlatformData.empty();
        }
    }

    /**
     * 从 B站平台拉取数据 —— 需 OAuth token。
     */
    private RawPlatformData fetchFromBilibili(String accountId) {
        if (bilibiliPlatformService == null || !bilibiliPlatformService.isAvailable()) {
            log.debug("[Audience] B站平台不可用，降级");
            return RawPlatformData.empty();
        }
        try {
            String result = bilibiliPlatformService.getVideoStats("", accountId);
            return new RawPlatformData(result, !isDegradedMessage(result));
        } catch (Exception e) {
            log.error("[Audience] B站数据拉取失败，降级", e);
            return RawPlatformData.empty();
        }
    }

    /**
     * 从快手平台拉取数据。
     */
    private RawPlatformData fetchFromKuaishou() {
        if (kuaishouPlatformService == null || !kuaishouPlatformService.isAvailable()) {
            log.debug("[Audience] 快手平台不可用，降级");
            return RawPlatformData.empty();
        }
        try {
            StringBuilder raw = new StringBuilder();
            raw.append(kuaishouPlatformService.getUserInfo("")).append('\n');
            raw.append(kuaishouPlatformService.queryVideoList("", 1, 20)).append('\n');
            return new RawPlatformData(raw.toString(), true);
        } catch (Exception e) {
            log.error("[Audience] 快手数据拉取失败，降级", e);
            return RawPlatformData.empty();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 画像组装
    // ════════════════════════════════════════════════════════════════

    /**
     * 将平台原始数据组装为三层受众画像。
     */
    private AudienceProfile assembleAudienceProfile(String accountId, RawPlatformData raw) {
        ParsedMetrics metrics = raw.hasRealData()
                ? metricsParser.parse(raw.text())
                : new ParsedMetrics();
        String rawText = raw.hasRealData() ? raw.text() : "";

        DemographicProfile demographic = buildDemographicProfile(rawText, metrics);
        BehaviorProfile behavior = buildBehaviorProfile(rawText, metrics);
        GrowthProfile growth = buildGrowthProfile(metrics);

        return new AudienceProfile(accountId, demographic, behavior, growth, null, null);
    }

    /**
     * 构建人口属性画像层。
     *
     * <p>性别与年龄分布当前为行业典型估算值（平台 API 暂未直接返回此类数据），
     * 后续可通过 {@link AudienceProfileProperties} 扩展为可配置项；
     * 地域分布则从原始文本中解析真实数据，无法提取时返回空列表。
     *
     * @param rawText 平台原始文本，用于解析地域分布
     * @param m       已解析的聚合指标
     */
    private DemographicProfile buildDemographicProfile(String rawText, ParsedMetrics m) {
        // 无真实数据时返回空画像，避免 hasData() 误判
        if (!m.hasData() && (rawText == null || rawText.isBlank())) {
            return DemographicProfile.empty();
        }

        long followerCount = m.netGrowth() > 0 ? m.netGrowth() : m.newUsers();
        // 性别分布：平台未直接返回时使用行业典型估算值（女性偏多，符合多数内容消费类账号特征）
        GenderDistribution gender = new GenderDistribution(0.35, 0.60, 0.05);
        // 地域分布：从原始文本中解析真实数据，无法提取时返回空列表（不使用硬编码默认值）
        List<RegionStat> regions = extractRegions(rawText, m);
        // 年龄分布：平台未直接返回时使用行业典型估算值（平台数据不可用时的经验估计）
        Map<String, Double> ageRanges = new HashMap<>();
        ageRanges.put("18-24", 0.35);
        ageRanges.put("25-30", 0.30);
        ageRanges.put("31-40", 0.25);
        ageRanges.put("40+", 0.10);
        AgeDistribution age = new AgeDistribution(ageRanges);

        return new DemographicProfile(followerCount, gender, regions, age);
    }

    /**
     * 构建行为偏好画像层。
     *
     * @param rawText 平台原始文本，用于解析活跃时段与内容偏好
     * @param m       已解析的聚合指标
     */
    private BehaviorProfile buildBehaviorProfile(String rawText, ParsedMetrics m) {
        // 无真实数据时返回空画像，避免 hasData() 误判
        if (!m.hasData() && (rawText == null || rawText.isBlank())) {
            return BehaviorProfile.empty();
        }
        List<TimeSlotActivity> slots = extractTimeSlots(rawText, m);
        List<TagPreference> tags = extractTagPreferences(rawText, m);
        InteractionTendency tendency = buildInteractionTendency(m);
        return new BehaviorProfile(slots, tags, tendency);
    }

    /**
     * 构建增长态势画像层。
     */
    private GrowthProfile buildGrowthProfile(ParsedMetrics m) {
        // 无真实数据时返回空画像，避免 hasData() 误判
        if (!m.hasData()) {
            return GrowthProfile.empty();
        }
        long netGrowth7d = m.netGrowth() > 0 ? m.netGrowth() : 0;
        long netGrowth30d = m.netGrowth() > 0 ? (long) (m.netGrowth() * 4.3) : 0;
        double growthRate = metricsParser.computeGrowthRate(m);
        Map<String, Double> channels = buildDefaultGrowthChannels();
        GrowthTrend trend = GrowthTrend.fromRate(growthRate,
                properties.getRapidGrowthThreshold(), properties.getDeclineThreshold());
        return new GrowthProfile(netGrowth7d, netGrowth30d, growthRate, channels, trend);
    }

    /**
     * 构建互动倾向。
     */
    private InteractionTendency buildInteractionTendency(ParsedMetrics m) {
        long total = m.likes() + m.commentCount() + m.shareCount() + m.collectCount();
        if (total == 0) {
            return InteractionTendency.empty();
        }
        return new InteractionTendency(
                (double) m.commentCount() / total,
                (double) m.collectCount() / total,
                (double) m.shareCount() / total,
                (double) m.likes() / total
        );
    }

    /**
     * 构建默认增长渠道分布。
     */
    private Map<String, Double> buildDefaultGrowthChannels() {
        Map<String, Double> channels = new HashMap<>();
        channels.put("搜索", 0.3);
        channels.put("推荐", 0.5);
        channels.put("分享", 0.2);
        return channels;
    }

    /**
     * 将平台原始数据组装为三层内容画像。
     */
    private ContentProfile assembleContentProfile(String accountId, RawPlatformData raw) {
        ParsedMetrics metrics = raw.hasRealData()
                ? metricsParser.parse(raw.text())
                : new ParsedMetrics();

        TopicDistribution topicDist = buildTopicDistribution(raw, metrics);
        PerformanceHistory performance = buildPerformanceHistory(metrics);
        ContentProfile.MonetizationProfile monetization = ContentProfile.MonetizationProfile.empty();

        return new ContentProfile(accountId, topicDist, performance, monetization, null, null);
    }

    /**
     * 构建选题分布画像层。
     */
    private TopicDistribution buildTopicDistribution(RawPlatformData raw, ParsedMetrics m) {
        List<TopicKeyword> keywords = extractTopicKeywords(raw.text());
        Map<ContentType, Double> typeRatio = buildDefaultContentTypeRatio();
        List<PlatformFit> platformFits = extractPlatformFits(raw.text(), m);
        return new TopicDistribution(keywords, typeRatio, platformFits);
    }

    /**
     * 构建历史表现画像层。
     */
    private PerformanceHistory buildPerformanceHistory(ParsedMetrics m) {
        double avgEngagement = m.engagementRate() > 0
                ? m.engagementRate()
                : metricsParser.computeEngagementRate(m);
        long avgReadCount = m.readCount() > 0 ? m.readCount() : m.playCount();
        String bestSlot = m.readCount() > 0 ? "20:00-22:00" : "未知";

        List<String> highTraits = new ArrayList<>();
        if (m.collectCount() > 0) highTraits.add("高收藏率内容受用户认可");
        if (m.shareCount() > 0) highTraits.add("高分享率内容具传播性");
        if (m.readFinishRate() > 0.3) highTraits.add("高完读率内容结构清晰");

        List<String> lowCauses = new ArrayList<>();
        if (m.readFinishRate() > 0 && m.readFinishRate() < 0.3) lowCauses.add("阅读完成率偏低，内容深度不足");
        if (avgEngagement > 0 && avgEngagement < 0.03) lowCauses.add("互动率低于行业均值");

        return new PerformanceHistory(avgEngagement, avgReadCount, bestSlot, highTraits, lowCauses);
    }

    /**
     * 构建默认内容类型配比。
     */
    private Map<ContentType, Double> buildDefaultContentTypeRatio() {
        Map<ContentType, Double> ratio = new HashMap<>();
        ratio.put(ContentType.TUTORIAL, 0.35);
        ratio.put(ContentType.PERSONAL_STORY, 0.25);
        ratio.put(ContentType.OPINION, 0.20);
        ratio.put(ContentType.TREND_ANALYSIS, 0.10);
        ratio.put(ContentType.LISTICLE, 0.10);
        return ratio;
    }

    // ════════════════════════════════════════════════════════════════
    // 缓存与持久化
    // ════════════════════════════════════════════════════════════════

    /**
     * 写入受众画像缓存、索引与 RAG 知识库。
     */
    private void cacheAndPersistAudience(AudienceProfile profile, String platform) {
        String accountId = profile.accountId();
        audienceCache.put(accountId, profile);
        profileIndex.put(accountId, new AccountMeta(accountId, platform, Instant.now()));
        persistAudienceToKnowledgeBase(profile);
    }

    /**
     * 写入内容画像缓存与 RAG 知识库。
     */
    private void cacheAndPersistContent(ContentProfile profile) {
        String accountId = profile.accountId();
        contentCache.put(accountId, profile);
        persistContentToKnowledgeBase(profile);
    }

    /**
     * 将受众画像 JSON 持久化到 RAG 知识库。
     */
    private void persistAudienceToKnowledgeBase(AudienceProfile profile) {
        if (!knowledgeBaseService.isAvailable()) {
            log.debug("[Audience] 知识库不可用，跳过受众画像持久化: accountId={}", profile.accountId());
            return;
        }
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("type", METADATA_TYPE_AUDIENCE_PROFILE);
            metadata.put("agent", "audience-profile");
            metadata.put("accountId", profile.accountId());
            metadata.put("hasData", String.valueOf(profile.hasData()));
            metadata.put("timestamp", Instant.now().toString());

            String content = "受众画像[" + profile.accountId() + "]\n" + json;
            boolean ok = knowledgeBaseService.ingest(content, metadata);
            if (ok) {
                log.debug("[Audience] 受众画像已持久化到知识库: accountId={}", profile.accountId());
            }
        } catch (Exception e) {
            log.error("[Audience] 受众画像持久化失败: accountId={}", profile.accountId(), e);
        }
    }

    /**
     * 将内容画像 JSON 持久化到 RAG 知识库。
     */
    private void persistContentToKnowledgeBase(ContentProfile profile) {
        if (!knowledgeBaseService.isAvailable()) {
            log.debug("[Audience] 知识库不可用，跳过内容画像持久化: accountId={}", profile.accountId());
            return;
        }
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("type", METADATA_TYPE_CONTENT_PROFILE);
            metadata.put("agent", "audience-profile");
            metadata.put("accountId", profile.accountId());
            metadata.put("hasData", String.valueOf(profile.hasData()));
            metadata.put("timestamp", Instant.now().toString());

            String content = "内容画像[" + profile.accountId() + "]\n" + json;
            boolean ok = knowledgeBaseService.ingest(content, metadata);
            if (ok) {
                log.debug("[Audience] 内容画像已持久化到知识库: accountId={}", profile.accountId());
            }
        } catch (Exception e) {
            log.error("[Audience] 内容画像持久化失败: accountId={}", profile.accountId(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // mergeWithBase 辅助
    // ════════════════════════════════════════════════════════════════

    /**
     * 将受众画像关键信息以可读文本追加到 targetAudience 字段。
     */
    private String buildEnrichedTargetAudience(String original, AudienceProfile audience) {
        StringBuilder sb = new StringBuilder();
        if (original != null && !original.isBlank()) {
            sb.append(original);
        }
        sb.append("\n[结构化受众画像]");

        DemographicProfile demo = audience.demographic();
        if (demo.followerCount() > 0) {
            sb.append("\n粉丝量级: ").append(formatCount(demo.followerCount()));
        }
        GenderDistribution gender = demo.genderDistribution();
        if (gender.hasData()) {
            sb.append("\n性别分布: 女性").append(pct(gender.femaleRatio()))
                    .append("，男性").append(pct(gender.maleRatio()));
        }
        if (!demo.regions().isEmpty()) {
            sb.append("\n地域TOP3: ");
            int limit = Math.min(3, demo.regions().size());
            for (int i = 0; i < limit; i++) {
                RegionStat r = demo.regions().get(i);
                if (i > 0) sb.append("、");
                sb.append(r.region()).append("(").append(pct(r.ratio())).append(")");
            }
        }

        BehaviorProfile behavior = audience.behavior();
        if (!behavior.activeTimeSlots().isEmpty()) {
            TimeSlotActivity top = behavior.activeTimeSlots().get(0);
            sb.append("\n活跃时段: ").append(top.timeSlot())
                    .append("（互动率").append(pct(top.avgEngagementRate())).append("）");
        }
        if (!behavior.tagPreferences().isEmpty()) {
            sb.append("\n内容偏好: ");
            int limit = Math.min(3, behavior.tagPreferences().size());
            for (int i = 0; i < limit; i++) {
                TagPreference t = behavior.tagPreferences().get(i);
                if (i > 0) sb.append("、");
                sb.append(t.tag()).append("(").append(pct(t.weight())).append(")");
            }
        }

        GrowthProfile growth = audience.growth();
        if (growth.netGrowth30d() != 0) {
            sb.append("\n30日增长: ").append(growth.netGrowth30d())
                    .append("（").append(pct(growth.followerGrowthRate())).append("）");
        }

        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 从原始文本中提取地域分布。
     *
     * <p>识别「地域: 广东(18%) 北京(12%)」「省份分布: 广东 18% 北京 12%」等格式，
     * 解析各省份/城市的占比与估算粉丝数。无法提取到地域数据时返回空列表（不使用硬编码默认值）。
     *
     * @param rawText 平台原始文本
     * @param m       已解析的聚合指标（用于估算各地域粉丝数）
     * @return 地域统计列表（按文本出现顺序，受 {@code regionStatCount} 限制条目数）；无数据时为空
     */
    private List<RegionStat> extractRegions(String rawText, ParsedMetrics m) {
        List<RegionStat> regions = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return regions;
        }
        Matcher rm = REGION_PATTERN.matcher(rawText);
        if (!rm.find()) {
            return regions;
        }
        String regionText = rm.group(1);
        long followerCount = m.netGrowth() > 0 ? m.netGrowth() : m.newUsers();
        Matcher em = REGION_ENTRY_PATTERN.matcher(regionText);
        while (em.find()) {
            String region = em.group(1);
            double ratio;
            try {
                ratio = Double.parseDouble(em.group(2)) / 100.0;
            } catch (NumberFormatException ignored) {
                continue;
            }
            long count = followerCount > 0 ? (long) (followerCount * ratio) : 0;
            regions.add(new RegionStat(region, ratio, count));
        }
        if (regions.isEmpty()) {
            return regions;
        }
        int limit = Math.max(1, properties.getRegionStatCount());
        if (regions.size() > limit) {
            regions = new ArrayList<>(regions.subList(0, limit));
        }
        return regions;
    }

    /**
     * 从原始文本中提取活跃时段分布。
     *
     * <p>按「- 标题:」切分内容块，从每个块中提取发布小时（支持完整日期时间格式与
     * Unix 时间戳形式的「创建时间/create_time」），归类到早高峰/午休/晚高峰/其他时段，
     * 并基于各块解析指标计算该时段的平均互动率。
     *
     * <p>降级策略：当无法从文本中提取任何时间信息时，返回仅含一个默认「20:00-22:00」
     * 时段的列表，其互动率取自聚合指标。
     *
     * @param rawText 平台原始文本
     * @param m       已解析的聚合指标（降级时用于估算默认时段互动率）
     * @return 活跃时段列表（按平均互动率降序）；无时间数据时返回单条默认时段
     */
    private List<TimeSlotActivity> extractTimeSlots(String rawText, ParsedMetrics m) {
        if (rawText == null || rawText.isBlank()) {
            return defaultTimeSlots(m);
        }
        List<ContentBlock> blocks = splitIntoContentBlocks(rawText);
        if (blocks.isEmpty()) {
            return defaultTimeSlots(m);
        }
        // 按时段累积各块的互动率
        Map<String, List<Double>> slotRates = new LinkedHashMap<>();
        for (ContentBlock block : blocks) {
            Integer hour = extractHour(block.text());
            if (hour == null) {
                continue;
            }
            String slot = classifyHour(hour);
            if (slot == null) {
                continue;
            }
            ParsedMetrics pm = metricsParser.parse(block.text());
            long read = Math.max(pm.readCount(), pm.playCount());
            long engagement = pm.likes() + pm.commentCount() + pm.shareCount() + pm.collectCount();
            double rate = read > 0 ? (double) engagement / read : 0.0;
            slotRates.computeIfAbsent(slot, k -> new ArrayList<>()).add(rate);
        }
        if (slotRates.isEmpty()) {
            return defaultTimeSlots(m);
        }
        int maxCount = slotRates.values().stream().mapToInt(List::size).max().orElse(1);
        List<TimeSlotActivity> slots = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : slotRates.entrySet()) {
            List<Double> rates = entry.getValue();
            double avg = rates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double activityLevel = (double) rates.size() / maxCount;
            slots.add(new TimeSlotActivity(entry.getKey(), activityLevel, avg));
        }
        slots.sort((a, b) -> Double.compare(b.avgEngagementRate(), a.avgEngagementRate()));
        return slots;
    }

    /**
     * 构建降级默认时段列表（仅含一个黄金时段）。
     *
     * @param m 已解析的聚合指标
     * @return 仅含「20:00-22:00」的单条时段列表
     */
    private List<TimeSlotActivity> defaultTimeSlots(ParsedMetrics m) {
        List<TimeSlotActivity> slots = new ArrayList<>();
        double baseEngagement = m.engagementRate() > 0 ? m.engagementRate()
                : metricsParser.computeEngagementRate(m);
        slots.add(new TimeSlotActivity("20:00-22:00", 1.0, baseEngagement > 0 ? baseEngagement : 0.068));
        return slots;
    }

    /**
     * 从原始文本中提取内容偏好标签。
     *
     * <p>按「- 标题:」提取所有标题，使用与
     * {@link com.contentops.analysis.tool.MetricsCalculator} 相同的 {@link #CATEGORY_KEYWORDS}
     * 关键词分类逻辑将每条标题归入内容类型，统计各类型出现次数并归一化为权重（总和为 1）。
     *
     * <p>降级策略：当文本中提取不到任何标题时返回空列表。
     *
     * @param rawText 平台原始文本
     * @param m       已解析的聚合指标（当前未直接使用，保留以备扩展）
     * @return 内容偏好标签列表（按权重降序，受 {@code maxTagPreferences} 限制条目数）；无标题时为空
     */
    private List<TagPreference> extractTagPreferences(String rawText, ParsedMetrics m) {
        List<TagPreference> tags = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return tags;
        }
        List<String> titles = new ArrayList<>();
        Matcher tm = TITLE_PATTERN.matcher(rawText);
        while (tm.find()) {
            String title = tm.group(1).trim();
            int lineEnd = title.indexOf('\n');
            if (lineEnd > 0) {
                title = title.substring(0, lineEnd).trim();
            }
            titles.add(title);
        }
        if (titles.isEmpty()) {
            return tags;
        }
        // 按内容类型计数（保持 LinkedHashMap 插入顺序）
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String cat : CATEGORY_KEYWORDS.keySet()) {
            counts.put(cat, 0);
        }
        for (String title : titles) {
            String category = matchCategory(title);
            counts.merge(category, 1, Integer::sum);
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return tags;
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count == 0) {
                continue;
            }
            double weight = (double) count / total;
            tags.add(new TagPreference(entry.getKey(), weight, count));
        }
        tags.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        int limit = Math.max(1, properties.getMaxTagPreferences());
        if (tags.size() > limit) {
            tags = new ArrayList<>(tags.subList(0, limit));
        }
        return tags;
    }

    /**
     * 将原始文本按「- 标题:」标记切分为多个内容块。
     *
     * <p>每个块的文本包含其下方的统计信息（阅读/播放/点赞/评论/时间等），
     * 与 {@link com.contentops.analysis.tool.MetricsCalculator} 的切分逻辑一致。
     *
     * @param rawText 平台原始文本
     * @return 内容块列表（标题 + 块文本）
     */
    private List<ContentBlock> splitIntoContentBlocks(String rawText) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return blocks;
        }
        Matcher m = TITLE_PATTERN.matcher(rawText);
        int lastEnd = -1;
        String lastTitle = null;
        while (m.find()) {
            if (lastTitle != null) {
                String blockText = rawText.substring(lastEnd, m.start());
                blocks.add(new ContentBlock(lastTitle, blockText));
            }
            lastTitle = m.group(1).trim();
            lastEnd = m.end();
        }
        if (lastTitle != null) {
            String blockText = rawText.substring(lastEnd);
            blocks.add(new ContentBlock(lastTitle, blockText));
        }
        return blocks;
    }

    /**
     * 从内容块文本中提取发布小时。
     *
     * <p>优先匹配完整日期时间格式（如「2024-01-15 21:30:00」），
     * 其次匹配「创建时间/create_time」标签的值（可为 Unix 时间戳或日期时间字符串）。
     *
     * @param blockText 单个内容块的文本
     * @return 小时（0-23），无法提取时返回 null
     */
    private Integer extractHour(String blockText) {
        if (blockText == null || blockText.isBlank()) {
            return null;
        }
        Matcher dt = DATETIME_PATTERN.matcher(blockText);
        if (dt.find()) {
            try {
                return Integer.parseInt(dt.group(4));
            } catch (NumberFormatException ignored) {
                // 继续尝试其他方式
            }
        }
        Matcher ct = CREATE_TIME_PATTERN.matcher(blockText);
        if (ct.find()) {
            String val = ct.group(1);
            if (EPOCH_PATTERN.matcher(val).matches()) {
                try {
                    return Instant.ofEpochSecond(Long.parseLong(val))
                            .atZone(CHINA_ZONE)
                            .getHour();
                } catch (NumberFormatException ignored) {
                    // 时间戳解析失败
                }
            } else {
                Matcher dt2 = DATETIME_PATTERN.matcher(val);
                if (dt2.find()) {
                    try {
                        return Integer.parseInt(dt2.group(4));
                    } catch (NumberFormatException ignored) {
                        // 忽略
                    }
                }
            }
        }
        return null;
    }

    /**
     * 将小时归类到时段，返回时段标识。
     *
     * @param hour 小时（0-23）
     * @return 时段标识（如「20:00-22:00」），非黄金时段返回「其他时段」；hour 为 null 时返回 null
     */
    private String classifyHour(Integer hour) {
        if (hour == null) {
            return null;
        }
        if (hour >= 7 && hour < 9) {
            return "07:00-09:00";
        }
        if (hour == 12) {
            return "12:00-13:00";
        }
        if (hour >= 20 && hour < 22) {
            return "20:00-22:00";
        }
        return "其他时段";
    }

    /**
     * 根据标题匹配内容类型关键词，返回首个命中的类型，否则返回「其他」。
     *
     * <p>分类逻辑与 {@link com.contentops.analysis.tool.MetricsCalculator} 保持一致。
     *
     * @param title 内容标题
     * @return 内容类型名称
     */
    private String matchCategory(String title) {
        if (title == null || title.isBlank()) {
            return "其他";
        }
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, String[]> entry : CATEGORY_KEYWORDS.entrySet()) {
            for (String kw : entry.getValue()) {
                if (lower.contains(kw.toLowerCase(java.util.Locale.ROOT))) {
                    return entry.getKey();
                }
            }
        }
        return "其他";
    }

    /**
     * 从原始文本中提取选题关键词。
     */
    private List<TopicKeyword> extractTopicKeywords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<TopicKeyword> keywords = new ArrayList<>();
        Pattern p = Pattern.compile("标题[：:]\\s*(.+)");
        Matcher matcher = p.matcher(text);
        int count = 0;
        while (matcher.find() && count < properties.getMaxTopicKeywords()) {
            String title = matcher.group(1).trim();
            int lineEnd = title.indexOf('\n');
            if (lineEnd > 0) title = title.substring(0, lineEnd).trim();
            keywords.add(new TopicKeyword(title, 1, LocalDate.now(), 0.5));
            count++;
        }
        return keywords;
    }

    /**
     * 从原始文本中提取平台适配度。
     */
    private List<PlatformFit> extractPlatformFits(String text, ParsedMetrics m) {
        List<PlatformFit> fits = new ArrayList<>();
        long avgRead = m.readCount() > 0 ? m.readCount() : m.playCount();
        double avgEngagement = m.engagementRate() > 0
                ? m.engagementRate()
                : metricsParser.computeEngagementRate(m);
        fits.add(new PlatformFit("公众号", avgRead, avgEngagement, 1));
        return fits;
    }

    /**
     * 判断平台返回是否为降级文案。
     */
    private boolean isDegradedMessage(String result) {
        return result == null || result.isBlank() || result.strip().startsWith("[");
    }

    /**
     * 归一化平台标识。
     */
    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return properties.getDefaultPlatform();
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
     * 校验 accountId 非空。
     */
    private void requireAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
    }

    /**
     * 将小数格式化为百分比整数。
     */
    private int pct(double ratio) {
        return (int) Math.round(ratio * 100);
    }

    /**
     * 将粉丝量级格式化为可读字符串。
     */
    private String formatCount(long count) {
        if (count >= 10000) {
            return String.format("%.1f万", count / 10000.0);
        }
        return String.valueOf(count);
    }

    /**
     * 获取缓存统计信息（用于监控与运维）。
     *
     * @return 缓存统计快照
     */
    public Map<String, Object> cacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("audienceCacheSize", audienceCache.estimatedSize());
        stats.put("contentCacheSize", contentCache.estimatedSize());
        stats.put("registeredAccounts", profileIndex.size());
        stats.put("audienceHitRate", audienceCache.stats().hitRate());
        stats.put("contentHitRate", contentCache.stats().hitRate());
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
     * 账号轻量索引元信息。
     *
     * @param accountId  账号 ID
     * @param platform   平台
     * @param lastUpdated 最近更新时间
     */
    public record AccountMeta(
            String accountId,
            String platform,
            Instant lastUpdated
    ) {
    }

    /**
     * 内容块：标题 + 该块对应的正文文本（用于时段与标签提取）。
     *
     * @param title 标题
     * @param text  该块正文文本
     */
    private record ContentBlock(String title, String text) {
    }
}
