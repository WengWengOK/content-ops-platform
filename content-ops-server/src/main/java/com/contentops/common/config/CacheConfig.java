package com.contentops.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存配置 — 使用 Caffeine 为低频变更数据提供本地缓存。
 *
 * <p>P2-15: 配置连接池参数和本地缓存。
 * 为频繁访问但低频变更的配置数据（平台配置、Agent阶段、Prompt模板）提供本地缓存，
 * 减少重复计算和远程调用开销。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_PLATFORM_CONFIG = "platform-config";
    public static final String CACHE_AGENT_STAGES = "agent-stages";
    public static final String CACHE_PROMPT_TEMPLATES = "prompt-templates";

    /**
     * 自定义 Caffeine CacheManager — 为不同缓存设置不同的过期策略。
     */
    @Bean
    public CacheManagerCustomizer<CaffeineCacheManager> cacheManagerCustomizer() {
        return cacheManager -> {
            cacheManager.registerCustomCache(CACHE_PLATFORM_CONFIG,
                    Caffeine.newBuilder()
                            .maximumSize(100)
                            .expireAfterWrite(30, TimeUnit.MINUTES)
                            .recordStats()
                            .build());
            cacheManager.registerCustomCache(CACHE_AGENT_STAGES,
                    Caffeine.newBuilder()
                            .maximumSize(10)
                            .expireAfterWrite(1, TimeUnit.HOURS)
                            .recordStats()
                            .build());
            cacheManager.registerCustomCache(CACHE_PROMPT_TEMPLATES,
                    Caffeine.newBuilder()
                            .maximumSize(200)
                            .expireAfterWrite(10, TimeUnit.MINUTES)
                            .recordStats()
                            .build());
        };
    }
}
