package com.contentops.common.profile.style;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 风格画像系统配置属性类（P0 改造）。
 *
 * <p>绑定到 {@code application.yml} 中的 {@code contentops.style-profile}：
 * <pre>
 * contentops:
 *   style-profile:
 *     analysis-mode: hybrid          # heuristic / llm / hybrid
 *     cache-ttl-minutes: 60          # 画像缓存 TTL（分钟）
 *     cache-maximum-size: 500        # 缓存最大条数
 *     similarity-threshold: 0.65     # 相似账号检索的最低相似度阈值
 *     similar-default-limit: 5       # 相似检索默认返回上限
 *     max-contents: 50               # 单次聚合分析的最大内容篇数
 *     domain-terms:                  # 领域术语词典（用于专业术语密度检测）
 *       - 矩阵
 *       - 转化
 *       - 留存
 *     llm-enabled: true              # 是否允许调用 LLM 增强分析
 * </pre>
 *
 * <p>该配置驱动 {@link StyleAnalysisService}（分析模式与术语词典）、{@link StyleProfileManager}
 * （缓存与相似度阈值）等组件的行为，全部参数均可通过配置文件覆盖，无需改代码。
 *
 * @see StyleAnalysisService
 * @see StyleProfileManager
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.style-profile")
public class StyleProfileProperties {

    /**
     * 分析模式：
     * <ul>
     *   <li>{@code heuristic} —— 仅启发式规则分析，不依赖任何外部 API（默认，保证核心功能可用）</li>
     *   <li>{@code llm} —— 仅 LLM 分析，ChatModel 不可用时自动降级到启发式</li>
     *   <li>{@code hybrid} —— 启发式为主，LLM 增强主观/视觉等字段（推荐）</li>
     * </ul>
     */
    private String analysisMode = "heuristic";

    /** 画像缓存 TTL（分钟），默认 60 分钟（1 小时）。 */
    private long cacheTtlMinutes = 60;

    /** 画像缓存最大条目数。 */
    private int cacheMaximumSize = 500;

    /** 相似账号检索的最低综合相似度阈值（0~1），低于此值的结果被过滤。 */
    private double similarityThreshold = 0.6;

    /** 相似检索默认返回上限（未显式指定 limit 时使用）。 */
    private int similarDefaultLimit = 5;

    /** 单次聚合分析的最大内容篇数，超出部分截断（防止分析耗时过长）。 */
    private int maxContents = 50;

    /**
     * 领域术语词典（用于专业术语密度检测）。
     * <p>配置后，{@link StyleAnalysisService} 会按这些术语在正文中匹配，计算术语密度；
     * 未配置（空列表）时回退到「生僻字比例」估算。
     */
    private List<String> domainTerms = List.of();

    /** 是否允许调用 LLM 增强分析（关闭后即使 analysisMode=llm/hybrid 也只走启发式）。 */
    private boolean llmEnabled = true;

    /**
     * 判断当前分析模式是否启用 LLM。
     *
     * @return {@code llm}/{@code hybrid} 模式且 {@link #llmEnabled} 为 true 时返回 true
     */
    public boolean isLlmAnalysisActive() {
        return llmEnabled && ("llm".equalsIgnoreCase(analysisMode) || "hybrid".equalsIgnoreCase(analysisMode));
    }
}
