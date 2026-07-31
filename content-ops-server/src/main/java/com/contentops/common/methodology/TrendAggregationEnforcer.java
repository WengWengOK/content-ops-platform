package com.contentops.common.methodology;

import com.contentops.common.dto.AnalysisReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 趋势聚合强制器（v2.2.0 方法论：「趋势而非单篇」，P2 优化: 硬强制 + 环比排序修复）。
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
 * <h3>P2 优化改进</h3>
 * <ul>
 *   <li><b>可配置硬强制</b>：{@link TrendAggregationProperties#isHardEnforce()} 为 true 时，
 *       校验失败抛出 {@link TrendEnforcementException} 阻断流程</li>
 *   <li><b>月份排序修复</b>：月度数据按实际日期排序，而非插入顺序</li>
 *   <li><b>环比计算修复</b>：逐月计算环比变化率，而非仅比较首尾</li>
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
        log.info("TrendAggregationEnforcer initialized: enabled={}, requireKeywords={}, minInsights={}, hardEnforce={}, minMonths={}",
                properties.isEnabled(), properties.getRequireKeywords(), properties.getMinInsights(),
                properties.isHardEnforce(), properties.getMinMonthsForTrend());
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
     * <h3>硬强制模式（P2 优化）</h3>
     * <p>当 {@link TrendAggregationProperties#isHardEnforce()} 为 true 且校验失败时：
     * <ul>
     *   <li>若无法从 rawData 提取足够月份数据（< {@link TrendAggregationProperties#getMinMonthsForTrend()}），
     *       抛出 {@link TrendEnforcementException} 阻断流程</li>
     *   <li>若能提取到足够数据，仍追加补充聚合后返回（修复后达标）</li>
     * </ul>
     *
     * @param report  待强化的分析报告（会被就地修改）
     * @param rawData DataAnalysisAgent 处理的原始数据文本（可为空）
     * @return 强化后的报告（同一引用）
     * @throws TrendEnforcementException 当硬强制模式开启且趋势数据不足时
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

        // 从原始数据中提取月度片段（已按日期排序）
        Map<String, Double> monthlyTrend = extractMonthlyTrend(rawData);

        // 硬强制模式：检查是否有足够的月份数据
        if (properties.isHardEnforce() && monthlyTrend.size() < properties.getMinMonthsForTrend()) {
            String errorMsg = String.format(
                    "趋势强制校验失败（硬强制模式）：仅提取到 %d 个月份数据（要求 ≥ %d）。%s",
                    monthlyTrend.size(), properties.getMinMonthsForTrend(), preCheck.message());
            log.error("[TrendEnforcement] 硬强制失败: {}", errorMsg);
            throw new TrendEnforcementException(errorMsg, preCheck);
        }

        // 确保 insights 可变
        if (report.getInsights() == null) {
            report.setInsights(new ArrayList<>());
        } else if (!(report.getInsights() instanceof ArrayList)) {
            report.setInsights(new ArrayList<>(report.getInsights()));
        }
        List<String> insights = report.getInsights();

        // 追加补充聚合洞察（含逐月环比分析）
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
        report.getChartData().put("trendEnforcedAt", LocalDate.now()
                .atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        // 若有足够月份数据，写入逐月环比分析
        if (monthlyTrend.size() >= 2) {
            report.getChartData().put("monthlyComparison", computeMonthlyComparison(monthlyTrend));
        }

        log.info("Monthly aggregation enforced: added {} monthly points, insights now={}",
                monthlyTrend.size(), insights.size());
        return report;
    }

    // ──────────────────── 内部工具方法 ────────────────────

    /**
     * 从原始数据文本中提取「月份 → 数值」映射（P2 优化: 按日期排序）。
     * <p>支持形如 {@code 2026-06: 1.2万}、{@code 06月 阅读18500} 的文本。
     * 提取后按月份的实际日期排序，而非插入顺序。
     *
     * @param rawData 原始数据文本
     * @return 按日期排序的月份→数值映射
     */
    private Map<String, Double> extractMonthlyTrend(String rawData) {
        Map<String, Double> rawTrend = new LinkedHashMap<>();
        if (rawData == null || rawData.isBlank()) {
            return rawTrend;
        }
        Matcher matcher = MONTH_DATA_PATTERN.matcher(rawData);
        while (matcher.find()) {
            String month = normalizeMonthKey(matcher.group(1));
            double value = parseValue(matcher.group(2), matcher.group(3));
            rawTrend.merge(month, value, Double::sum);
        }

        // P2 优化: 按实际日期排序
        Map<String, Double> sortedTrend = sortTrendByDate(rawTrend);

        if (sortedTrend.isEmpty()) {
            log.debug("No explicit monthly data found in rawData, will append placeholder aggregation");
        }
        return sortedTrend;
    }

    /**
     * 将月份字符串归一化为 "YYYY-MM" 格式。
     * <p>支持 "2026-06"、"2026年6月"、"06月"（补全当前年份）等格式。
     */
    private String normalizeMonthKey(String rawMonth) {
        String cleaned = rawMonth.trim();
        // 提取年份和月份
        Matcher m = Pattern.compile("(\\d{4})?[-/年]?(\\d{1,2})月?").matcher(cleaned);
        if (m.matches()) {
            String year = m.group(1);
            int month = Integer.parseInt(m.group(2));
            if (year == null) {
                year = String.valueOf(YearMonth.now().getYear());
            }
            return String.format("%s-%02d", year, month);
        }
        return cleaned;
    }

    /**
     * 按月份的实际日期排序趋势数据（P2 优化）。
     */
    private Map<String, Double> sortTrendByDate(Map<String, Double> trend) {
        Map<String, Double> sorted = new LinkedHashMap<>();
        trend.entrySet().stream()
                .sorted(Comparator.comparing(e -> parseYearMonth(e.getKey())))
                .forEachOrdered(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /**
     * 将 "YYYY-MM" 字符串解析为 {@link YearMonth} 用于排序。
     */
    private YearMonth parseYearMonth(String key) {
        try {
            return YearMonth.parse(key, DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (Exception e) {
            // 解析失败时返回当前月，保持相对顺序
            return YearMonth.now();
        }
    }

    /**
     * 计算逐月环比变化率（P2 优化: 替代仅比较首尾的逻辑）。
     *
     * @param monthlyTrend 按日期排序的月度数据
     * @return 逐月环比变化列表
     */
    private List<Map<String, Object>> computeMonthlyComparison(Map<String, Double> monthlyTrend) {
        List<Map<String, Object>> comparisons = new ArrayList<>();
        List<String> months = new ArrayList<>(monthlyTrend.keySet());

        for (int i = 1; i < months.size(); i++) {
            String prevMonth = months.get(i - 1);
            String currMonth = months.get(i);
            double prevValue = monthlyTrend.get(prevMonth);
            double currValue = monthlyTrend.get(currMonth);

            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("period", prevMonth + " → " + currMonth);
            comparison.put("previous", prevValue);
            comparison.put("current", currValue);
            comparison.put("change", currValue - prevValue);
            comparison.put("changeRate", formatChangeRate(prevValue, currValue));
            comparison.put("direction", currValue >= prevValue ? "增长" : "下降");
            comparisons.add(comparison);
        }
        return comparisons;
    }

    /**
     * 格式化环比变化率为百分比字符串。
     */
    private String formatChangeRate(double prev, double curr) {
        if (prev == 0) {
            return curr > 0 ? "+∞" : "N/A";
        }
        double rate = (curr - prev) / Math.abs(prev) * 100.0;
        return String.format(Locale.ROOT, "%+.1f%%", rate);
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
     * 构造补充聚合洞察文本（P2 优化: 含逐月环比分析）。
     * <p>有月度数据时输出具体的逐月环比趋势摘要；无数据时输出占位聚合说明。
     */
    private String buildSupplementaryInsight(Map<String, Double> monthlyTrend, ValidationResult preCheck) {
        if (monthlyTrend.isEmpty()) {
            return "[月度聚合] 原始数据未显式包含月度片段，已标记待人工补充月度趋势分析"
                    + "（缺失关键词: " + preCheck.missingKeywords() + "）。";
        }

        List<String> months = new ArrayList<>(monthlyTrend.keySet());
        StringBuilder sb = new StringBuilder();
        sb.append("[月度聚合] 已自动补充月度趋势：覆盖 ").append(months.size()).append(" 个月");

        if (months.size() >= 2) {
            // 逐月环比分析
            sb.append("，逐月环比：");
            for (int i = 1; i < months.size(); i++) {
                double prev = monthlyTrend.get(months.get(i - 1));
                double curr = monthlyTrend.get(months.get(i));
                String rate = formatChangeRate(prev, curr);
                sb.append(months.get(i - 1)).append("→").append(months.get(i))
                        .append("(").append(rate).append(")");
                if (i < months.size() - 1) {
                    sb.append("，");
                }
            }
        } else {
            sb.append("（").append(months.get(0)).append("）");
        }

        // 总体趋势方向
        if (months.size() >= 2) {
            double first = monthlyTrend.get(months.get(0));
            double last = monthlyTrend.get(months.get(months.size() - 1));
            String overall = formatOverallTrend(first, last);
            sb.append("。整体").append(overall);
        }

        sb.append("。建议人工复核并补充同比维度。");
        return sb.toString();
    }

    /**
     * 计算整体趋势方向描述。
     */
    private String formatOverallTrend(double first, double last) {
        if (first == 0) {
            return "趋势基线为 0，环比不可比";
        }
        double change = (last - first) / Math.abs(first) * 100.0;
        String direction = change >= 0 ? "增长" : "下降";
        return String.format(Locale.ROOT, "%s %.1f%%", direction, Math.abs(change));
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
