package com.contentops.common.methodology;

import com.contentops.common.dto.AnalysisReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 趋势聚合强制器（v2.2.0 方法论：「趋势而非单篇」）。
 *
 * <p>方法论约束：DataAnalysisAgent 输出分析报告前，强制检查是否包含月度聚合数据。
 * 单篇数据容易把运营决策带偏（一篇爆款不代表趋势，一篇低效也不代表衰退），
 * 因此报告必须呈现「趋势」——环比、同比、月度聚合。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #validateTrendCoverage(AnalysisReport)} — 校验报告是否满足趋势覆盖要求，
 *       返回包含命中/缺失关键词的结构化结果</li>
 *   <li>{@link #enforceMonthlyAggregation(AnalysisReport, String)} — 若报告缺少月度趋势，
 *       自动从原始数据中提取月度片段并追加补充聚合，保证输出始终含趋势维度</li>
 * </ul>
 *
 * <p>调用时机：DataAnalysisAgent 在生成 AnalysisReport 之后、返回给上游/持久化之前调用
 * {@code enforceMonthlyAggregation}；任何需要前置预检的环节可单独调用 {@code validateTrendCoverage}。
 */
@Slf4j
@Component
public class TrendAggregationEnforcer {

    /** 月度关键词（中英文），用于判定 insights 是否已含月度维度 */
    private static final List<String> MONTHLY_KEYWORDS = List.of("月度", "monthly", "monthlyTrend");

    /** 从原始数据文本中提取「月份 + 数值」的模式，例如 "2026-06: 1.2万" 或 "06月 阅读18500" */
    private static final Pattern MONTH_DATA_PATTERN = Pattern.compile(
            "(\\d{4}[-/年]?\\d{1,2}月?)\\s*[:：\\s]*\\s*(\\d+(?:\\.\\d+)?)\\s*(万|千|w|k)?",
            Pattern.CASE_INSENSITIVE);

    private final TrendAggregationProperties properties;

    public TrendAggregationEnforcer(TrendAggregationProperties properties) {
        this.properties = properties;
        log.info("TrendAggregationEnforcer initialized: enabled={}, requireKeywords={}, minInsights={}",
                properties.isEnabled(), properties.getRequireKeywords(), properties.getMinInsights());
    }

    /**
     * 校验报告是否满足趋势覆盖要求。
     *
     * <p>校验规则（启用时）：
     * <ol>
     *   <li>insights 非空且条数 ≥ {@link TrendAggregationProperties#getMinInsights()}</li>
     *   <li>insights 文本中至少命中一个 {@link TrendAggregationProperties#getRequireKeywords()} 关键词</li>
     * </ol>
     * 关闭时直接返回 valid=true 的通过结果，便于灰度。
     *
     * @param report 待校验的分析报告
     * @return 结构化校验结果
     */
    public ValidationResult validateTrendCoverage(AnalysisReport report) {
        if (!properties.isEnabled()) {
            log.debug("Trend enforcement disabled, validation auto-passed");
            return new ValidationResult(true, List.of(), List.of(), 0,
                    properties.getMinInsights(), "趋势强制校验已关闭，自动通过");
        }

        List<String> insights = report != null ? report.getInsights() : null;
        int insightsCount = insights != null ? insights.size() : 0;

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        String joined = joinInsights(insights);

        for (String kw : properties.getRequireKeywords()) {
            if (containsIgnoreCase(joined, kw)) {
                matched.add(kw);
            } else {
                missing.add(kw);
            }
        }

        boolean enoughInsights = insightsCount >= properties.getMinInsights();
        boolean hasTrendKeyword = !matched.isEmpty();
        boolean valid = enoughInsights && hasTrendKeyword;

        String message = buildValidationMessage(valid, enoughInsights, hasTrendKeyword,
                insightsCount, matched, missing);
        if (valid) {
            log.debug("Trend coverage OK: insights={}, matched={}", insightsCount, matched);
        } else {
            log.warn("Trend coverage insufficient: {}", message);
        }
        return new ValidationResult(valid, matched, missing, insightsCount,
                properties.getMinInsights(), message);
    }

    /**
     * 强制月度聚合：若报告缺少月度趋势数据，自动追加补充聚合。
     *
     * <p>执行流程：
     * <ol>
     *   <li>调用 {@link #validateTrendCoverage} 预检</li>
     *   <li>若已通过（含趋势关键词且 insights 充足），直接返回，不修改</li>
     *   <li>否则从 {@code rawData} 中提取月度数据片段，生成补充聚合洞察</li>
     *   <li>将补充洞察追加到 {@code report.insights}，并把月度聚合写入 {@code report.chartData}</li>
     * </ol>
     *
     * <p>该方法会就地修改传入的 report（insights、chartData 字段），并返回同一引用，
     * 便于链式调用。当功能关闭或 rawData 为空时，仍会追加一条占位聚合说明，
     * 确保报告不会「完全没有趋势维度」。
     *
     * @param report  待强化的分析报告（会被就地修改）
     * @param rawData DataAnalysisAgent 处理的原始数据文本（可为空）
     * @return 强化后的报告（同一引用）
     */
    public AnalysisReport enforceMonthlyAggregation(AnalysisReport report, String rawData) {
        if (report == null) {
            log.warn("enforceMonthlyAggregation received null report, skip");
            return null;
        }

        if (!properties.isEnabled()) {
            log.debug("Trend enforcement disabled, return report unchanged");
            return report;
        }

        ValidationResult preCheck = validateTrendCoverage(report);
        if (preCheck.valid()) {
            log.debug("Report already covers monthly trend, no enforcement needed");
            return report;
        }

        log.info("Enforcing monthly aggregation: insights={}, missing={}, extracting from rawData ({} chars)",
                preCheck.insightsCount(), preCheck.missingKeywords(),
                rawData != null ? rawData.length() : 0);

        // 确保 insights 可变
        if (report.getInsights() == null) {
            report.setInsights(new ArrayList<>());
        } else if (!(report.getInsights() instanceof ArrayList)) {
            report.setInsights(new ArrayList<>(report.getInsights()));
        }
        List<String> insights = report.getInsights();

        // 从原始数据中提取月度片段
        Map<String, Double> monthlyTrend = extractMonthlyTrend(rawData);

        // 追加补充聚合洞察
        String supplementary = buildSupplementaryInsight(monthlyTrend, preCheck);
        insights.add(supplementary);
        if (insights.size() < properties.getMinInsights()) {
            insights.add("[趋势补充] 建议结合环比/同比维度复核数据波动，避免依据单篇表现下结论。");
        }

        // 写入 chartData 供前端可视化呈现月度趋势
        if (report.getChartData() == null) {
            report.setChartData(new LinkedHashMap<>());
        } else if (!(report.getChartData() instanceof LinkedHashMap)) {
            report.setChartData(new LinkedHashMap<>(report.getChartData()));
        }
        report.getChartData().put("monthlyTrend", monthlyTrend);
        report.getChartData().put("trendEnforced", true);
        report.getChartData().put("trendEnforcedAt", LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        log.info("Monthly aggregation enforced: added {} monthly points, insights now={}",
                monthlyTrend.size(), insights.size());
        return report;
    }

    // ──────────────────── 内部工具方法 ────────────────────

    /**
     * 从原始数据文本中提取「月份 → 数值」映射。
     * <p>支持形如 {@code 2026-06: 1.2万}、{@code 06月 阅读18500} 的文本。
     * 提取不到时返回空 Map（不影响主流程，仅不补充具体数值）。
     */
    private Map<String, Double> extractMonthlyTrend(String rawData) {
        Map<String, Double> trend = new LinkedHashMap<>();
        if (rawData == null || rawData.isBlank()) {
            return trend;
        }
        Matcher matcher = MONTH_DATA_PATTERN.matcher(rawData);
        while (matcher.find()) {
            String month = matcher.group(1);
            double value = parseValue(matcher.group(2), matcher.group(3));
            trend.merge(month, value, Double::sum);
        }
        if (trend.isEmpty()) {
            log.debug("No explicit monthly data found in rawData, will append placeholder aggregation");
        }
        return trend;
    }

    private double parseValue(String number, String unit) {
        if (number == null || number.isBlank()) {
            return 0.0;
        }
        double v = Double.parseDouble(number);
        if (unit != null) {
            String u = unit.toLowerCase(Locale.ROOT);
            if (u.equals("万") || u.equals("w")) {
                v *= 10_000;
            } else if (u.equals("千") || u.equals("k")) {
                v *= 1_000;
            }
        }
        return v;
    }

    /**
     * 构造补充聚合洞察文本。
     * <p>有月度数据时输出具体的环比/同比趋势摘要；无数据时输出占位聚合说明。
     */
    private String buildSupplementaryInsight(Map<String, Double> monthlyTrend, ValidationResult preCheck) {
        if (monthlyTrend.isEmpty()) {
            return "[月度聚合] 原始数据未显式包含月度片段，已标记待人工补充月度趋势分析"
                    + "（缺失关键词: " + preCheck.missingKeywords() + "）。";
        }
        List<String> months = new ArrayList<>(monthlyTrend.keySet());
        String first = months.get(0);
        String last = months.get(months.size() - 1);
        double firstV = monthlyTrend.get(first);
        double lastV = monthlyTrend.get(last);
        String ratio = formatRatio(firstV, lastV);
        return "[月度聚合] 已自动补充月度趋势：覆盖 " + months.size() + " 个月（"
                + first + "→" + last + "），" + ratio
                + "。建议人工复核并补充同比维度。";
    }

    /** 计算环比描述（last 相对 first） */
    private String formatRatio(double first, double last) {
        if (first == 0) {
            return "首期基线为 0，环比不可比";
        }
        double change = (last - first) / Math.abs(first) * 100.0;
        String direction = change >= 0 ? "增长" : "下降";
        return "区间" + direction + String.format(Locale.ROOT, "%.1f%%", Math.abs(change));
    }

    private String joinInsights(List<String> insights) {
        if (insights == null || insights.isEmpty()) {
            return "";
        }
        return String.join(" ", insights);
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        return text != null && text.toLowerCase(Locale.ROOT)
                .contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String buildValidationMessage(boolean valid, boolean enoughInsights,
                                          boolean hasTrendKeyword, int insightsCount,
                                          List<String> matched, List<String> missing) {
        if (valid) {
            return "趋势覆盖通过：insights=" + insightsCount + "，命中关键词=" + matched;
        }
        StringBuilder sb = new StringBuilder("趋势覆盖不足：");
        if (!enoughInsights) {
            sb.append("insights 仅 ").append(insightsCount)
                    .append(" 条（要求≥").append(properties.getMinInsights()).append("）；");
        }
        if (!hasTrendKeyword) {
            sb.append("未命中任何趋势关键词，缺失=").append(missing).append("；");
        }
        sb.append("命中=").append(matched);
        return sb.toString();
    }

    /**
     * 趋势覆盖校验结果。
     *
     * @param valid               是否通过校验
     * @param matchedKeywords     已命中的要求关键词
     * @param missingKeywords     缺失的要求关键词
     * @param insightsCount       当前 insights 条数
     * @param minInsightsRequired 要求的最少 insights 条数
     * @param message             人类可读的校验结论
     */
    public record ValidationResult(
            boolean valid,
            List<String> matchedKeywords,
            List<String> missingKeywords,
            int insightsCount,
            int minInsightsRequired,
            String message
    ) {}
}
