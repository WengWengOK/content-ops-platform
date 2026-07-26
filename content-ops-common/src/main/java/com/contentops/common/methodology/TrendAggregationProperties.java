package com.contentops.common.methodology;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 趋势聚合强制器配置（v2.2.0 方法论：「趋势而非单篇」）。
 *
 * <p>绑定到 application.yml 中的 {@code contentops.methodology.trend-enforcement}：
 * <pre>
 * contentops:
 *   methodology:
 *     trend-enforcement:
 *       enabled: true
 *       require-keywords: ["月度", "趋势", "环比", "同比"]
 *       min-insights: 3
 * </pre>
 *
 * <p>该配置驱动 {@link TrendAggregationEnforcer}：在 DataAnalysisAgent 输出分析报告前，
 * 强制校验报告是否包含月度聚合数据；若缺失则自动追加补充聚合，确保最终输出始终
 * 呈现「趋势」而非孤立的单篇数据，避免运营决策被单点波动误导。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.methodology.trend-enforcement")
public class TrendAggregationProperties {

    /** 是否启用趋势聚合强制校验（关闭时 enforceMonthlyAggregation 直接透传，不做任何修改） */
    private boolean enabled = true;

    /**
     * 报告 insights 中必须命中的关键词列表。
     * <p>校验逻辑：只要 insights 里出现任一关键词即视为「已包含月度趋势」，
     * 全部缺失则触发补充聚合。默认覆盖中文常见趋势表述。
     */
    private List<String> requireKeywords = List.of("月度", "趋势", "环比", "同比");

    /** insights 列表的最少条数，低于该值视为覆盖不足 */
    private int minInsights = 3;
}
