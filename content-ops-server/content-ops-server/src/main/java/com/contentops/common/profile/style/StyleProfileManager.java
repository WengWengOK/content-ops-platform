package com.contentops.common.profile.style;

import com.contentops.common.config.CacheConfig;
import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.knowledge.KnowledgeBaseService.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 风格画像管理服务（P0 改造）。
 *
 * <p>负责风格画像的全生命周期管理：创建、增量更新、查询、列表、删除、相似检索与刷新。
 *
 * <h3>持久化与检索</h3>
 * <ul>
 *   <li><b>RAG 知识库持久化</b>：画像创建/更新时，将「风格签名 + 画像 JSON」写入 PGVector，
 *       metadata {@code type="style_profile"}，实现画像的持久化与跨会话相似检索</li>
 *   <li><b>风格签名向量化</b>：签名文本经 KnowledgeBaseService 的 BGE 模型向量化后存入 PGVector，
 *       支持「查找风格相似账号」语义检索</li>
 *   <li><b>会话级注册表</b>：进程内 {@link ConcurrentHashMap} 作为精确 CRUD 的权威存储
 *       （accountId 精确匹配）；PGVector 提供持久化副本与相似检索能力</li>
 * </ul>
 *
 * <h3>缓存</h3>
 * <p>{@link #getProfile} 使用 Caffeine 缓存（{@link CacheConfig#CACHE_STYLE_PROFILES}，1 小时 TTL），
 * 创建/更新/删除/刷新时自动失效对应条目。
 *
 * <h3>降级策略</h3>
 * <p>当 KnowledgeBaseService（PGVector）不可用时，画像仍可在内存注册表中正常 CRUD，
 * 仅相似检索返回空结果并记录告警，核心功能不中断。
 *
 * @see StyleAnalysisService
 * @see KnowledgeBaseService
 * @see CacheConfig
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StyleProfileManager {

    /** RAG 知识库中风格画像的 metadata type 标识。 */
    public static final String STYLE_PROFILE_TYPE = "style_profile";

    private final StyleAnalysisService analysisService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final StyleProfileProperties properties;
    private final ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper> objectMapperProvider;
    private final ObjectMapper fallbackObjectMapper = new ObjectMapper();

    /** 会话级画像注册表：accountId → 画像（精确 CRUD 权威存储）。 */
    private final Map<String, StyleProfile> registry = new ConcurrentHashMap<>();

    /**
     * 为指定账号创建风格画像。
     *
     * <p>流程：分析内容聚合 → 打 accountId 标签 → 写入注册表 → 持久化到 RAG → 失效缓存。
     *
     * @param accountId 账号 ID
     * @param contents  该账号的历史作品列表
     * @return 创建的风格画像
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_STYLE_PROFILES, key = "#accountId")
    public StyleProfile createProfile(String accountId, List<String> contents) {
        requireAccountId(accountId);
        StyleProfile profile = analysisService.buildProfile(contents).withAccountId(accountId);
        registry.put(accountId, profile);
        persistProfile(profile);
        log.info("[Style] 创建风格画像: accountId={}, sampleCount={}", accountId, profile.sampleCount());
        return profile;
    }

    /**
     * 增量更新画像：将新内容分析与已有画像按样本数加权合并。
     *
     * @param accountId   账号 ID
     * @param newContents 新增的作品列表
     * @return 更新后的风格画像；账号不存在时自动创建
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_STYLE_PROFILES, key = "#accountId")
    public StyleProfile updateProfile(String accountId, List<String> newContents) {
        requireAccountId(accountId);
        StyleProfile existing = registry.get(accountId);
        StyleProfile incremental = analysisService.buildProfile(newContents).withAccountId(accountId);
        StyleProfile updated = existing == null ? incremental : existing.merge(incremental);
        updated = updated.withAccountId(accountId);
        registry.put(accountId, updated);
        persistProfile(updated);
        log.info("[Style] 增量更新风格画像: accountId={}, sampleCount={}", accountId, updated.sampleCount());
        return updated;
    }

    /**
     * 获取指定账号的风格画像（命中 Caffeine 缓存）。
     *
     * @param accountId 账号 ID
     * @return 风格画像；不存在时返回 {@link Optional#empty()}
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_STYLE_PROFILES, key = "#accountId",
            unless = "#result == null || !#result.isPresent()")
    public Optional<StyleProfile> getProfile(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(registry.get(accountId));
    }

    /**
     * 列出所有已创建的风格画像。
     *
     * @return 画像列表（按 accountId 排序）
     */
    public List<StyleProfile> listProfiles() {
        List<StyleProfile> list = new ArrayList<>(registry.values());
        list.sort((a, b) -> {
            String idA = a.accountId() == null ? "" : a.accountId();
            String idB = b.accountId() == null ? "" : b.accountId();
            return idA.compareTo(idB);
        });
        return list;
    }

    /**
     * 删除指定账号的风格画像。
     *
     * <p>注：当前仅从会话级注册表与缓存中移除；PGVector 中的历史向量化副本需通过其生命周期管理清理
     * （KnowledgeBaseService 暂未提供按 metadata 删除的接口）。
     *
     * @param accountId 账号 ID
     * @return 是否删除成功（账号不存在返回 false）
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_STYLE_PROFILES, key = "#accountId")
    public boolean deleteProfile(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return false;
        }
        StyleProfile removed = registry.remove(accountId);
        if (removed != null) {
            log.info("[Style] 删除风格画像: accountId={}", accountId);
            return true;
        }
        log.warn("[Style] 删除风格画像失败，账号不存在: accountId={}", accountId);
        return false;
    }

    /**
     * 查找风格相似的账号。
     *
     * <p>将目标画像的风格签名向量化后，在 PGVector 中检索 {@code type="style_profile"} 的相似画像，
     * 过滤掉自身并按 {@link StyleProfileProperties#getSimilarityThreshold()} 阈值筛选。
     *
     * @param styleProfile 目标风格画像
     * @param limit        返回上限；{@code <=0} 时使用默认上限
     * @return 相似账号匹配列表（按相似度降序）
     */
    public List<SimilarStyleMatch> findSimilarStyle(StyleProfile styleProfile, int limit) {
        if (styleProfile == null) {
            return List.of();
        }
        if (!knowledgeBaseService.isAvailable()) {
            log.warn("[Style] 知识库不可用，相似风格检索返回空结果");
            return List.of();
        }
        int max = limit > 0 ? limit : properties.getSimilarDefaultLimit();
        String signature = analysisService.extractStyleSignature(styleProfile);
        List<SearchResult> results = knowledgeBaseService.searchByType(signature, STYLE_PROFILE_TYPE, max + 5);
        String selfAccountId = styleProfile.accountId();
        List<SimilarStyleMatch> matches = new ArrayList<>();
        for (SearchResult r : results) {
            String accountId = r.metadata().get("accountId");
            if (accountId == null || accountId.isBlank()) {
                continue;
            }
            if (accountId.equals(selfAccountId)) {
                continue;
            }
            if (r.score() < properties.getSimilarityThreshold()) {
                continue;
            }
            matches.add(new SimilarStyleMatch(accountId, round(r.score()), r.metadata()));
        }
        matches.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return matches.size() > max ? matches.subList(0, max) : matches;
    }

    /**
     * 刷新指定账号的画像：重新持久化到 RAG 知识库并失效缓存。
     *
     * <p>用于 PGVector 数据丢失后恢复，或强制下次查询重新加载。账号不存在时返回空。
     *
     * @param accountId 账号 ID
     * @return 刷新后的画像；账号不存在时返回 {@link Optional#empty()}
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_STYLE_PROFILES, key = "#accountId")
    public Optional<StyleProfile> refresh(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        StyleProfile existing = registry.get(accountId);
        if (existing == null) {
            log.warn("[Style] 刷新失败，账号不存在: accountId={}", accountId);
            return Optional.empty();
        }
        persistProfile(existing);
        log.info("[Style] 刷新风格画像: accountId={}", accountId);
        return Optional.of(existing);
    }

    // ════════════════════════════════════════════════════════════════
    //  持久化
    // ════════════════════════════════════════════════════════════════

    /**
     * 将画像持久化到 RAG 知识库（PGVector），metadata type="style_profile"。
     *
     * <p>存储内容 = 风格签名 + 画像 JSON，签名部分用于向量化相似检索，JSON 部分用于持久化留存。
     * 知识库不可用时记录告警但不中断主流程。
     */
    private void persistProfile(StyleProfile profile) {
        if (profile == null || profile.accountId() == null) {
            return;
        }
        if (!knowledgeBaseService.isAvailable()) {
            log.warn("[Style] 知识库不可用，画像仅存于内存注册表: accountId={}", profile.accountId());
            return;
        }
        try {
            String signature = analysisService.extractStyleSignature(profile);
            String json = resolveObjectMapper().writeValueAsString(profile);
            String content = signature + "\n---PROFILE_JSON---\n" + json;

            Map<String, String> metadata = new HashMap<>();
            metadata.put("type", STYLE_PROFILE_TYPE);
            metadata.put("accountId", profile.accountId());
            metadata.put("sampleCount", String.valueOf(profile.sampleCount()));
            metadata.put("createdAt", profile.createdAt() == null ? Instant.now().toString() : profile.createdAt().toString());
            metadata.put("timestamp", Instant.now().toString());

            boolean ok = knowledgeBaseService.ingest(content, metadata);
            if (ok) {
                log.debug("[Style] 画像已持久化到知识库: accountId={}", profile.accountId());
            }
        } catch (Exception e) {
            log.warn("[Style] 画像持久化失败，不影响内存注册表: accountId={}, err={}",
                    profile.accountId(), e.getMessage());
        }
    }

    /** 优先使用 Spring 容器中的 ObjectMapper（已注册 JavaTimeModule），否则回退到本地实例。 */
    private ObjectMapper resolveObjectMapper() {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        return mapper != null ? mapper : fallbackObjectMapper;
    }

    // ════════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════════

    /** 校验 accountId 非空。 */
    private void requireAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
    }

    /** 保留四位小数。 */
    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * 相似风格账号匹配结果。
     *
     * @param accountId 相似账号 ID
     * @param similarity 相似度（0~1）
     * @param metadata 知识库中该画像的元数据
     */
    public record SimilarStyleMatch(
            String accountId,
            double similarity,
            Map<String, String> metadata
    ) {}
}
