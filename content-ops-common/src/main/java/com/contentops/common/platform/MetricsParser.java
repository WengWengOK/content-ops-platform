package com.contentops.common.platform;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for extracting numerical metrics from the text output of platform services.
 *
 * <p>P1 ⑥: Replaces hardcoded values in OptimizeTools with dynamically parsed
 * real metrics from the analysis data produced by AnalysisTools / platform services.
 *
 * <p>Understands the text formats produced by:
 * <ul>
 *   <li>{@link WechatPlatformService#formatArticleReadData} — read counts, share counts</li>
 *   <li>{@link WechatPlatformService#formatUserSummary} — new/cancel users, net growth</li>
 *   <li>{@link WechatPlatformService#formatArticleTotalDetail} — read finish rate, avg read time, comments, collects</li>
 *   <li>Douyin / Bilibili / Kuaishou video stats — play count, likes, comments, shares</li>
 *   <li>Xiaohongshu note stats — likes, collects, comments</li>
 * </ul>
 *
 * <p>The parser is defensive: if a metric cannot be found in the input text,
 * the corresponding field stays at its default (0 or 0.0) and a flag is set
 * so callers can detect partial data.
 */
@Component
public class MetricsParser {

    // ════════════════ Public API ════════════════

    /**
     * Parse all extractable metrics from the given analysis text.
     *
     * @param text the combined raw data text from platform APIs
     * @return a {@link ParsedMetrics} record with all parsed values
     */
    public ParsedMetrics parse(String text) {
        if (text == null || text.isBlank()) {
            return new ParsedMetrics();
        }

        return new ParsedMetrics(
                extractLong(text, "阅读人数", "阅读量"),
                extractLong(text, "阅读次数", "播放量", "播放"),
                extractLong(text, "分享人数"),
                extractLong(text, "分享次数", "转发量", "转发"),
                extractLong(text, "点赞", "点赞数"),
                extractLong(text, "评论数", "评论"),
                extractLong(text, "收藏人数", "收藏数", "收藏"),
                extractLong(text, "新增"),
                extractLong(text, "取消"),
                extractLong(text, "净增粉丝", "净增"),
                extractDouble(text, "阅读完成率"),
                extractDouble(text, "平均阅读时长"),
                extractDouble(text, "互动率"),
                extractDouble(text, "阅读送达率"),
                extractDouble(text, "完播率"),
                extractDouble(text, "环比")
        );
    }

    /**
     * Compute an engagement rate from parsed metrics.
     *
     * <p>Engagement = (likes + comments + shares) / max(readCount, 1)
     *
     * @param m parsed metrics
     * @return engagement rate as a decimal (e.g. 0.061 for 6.1%), or 0 if no data
     */
    public double computeEngagementRate(ParsedMetrics m) {
        long totalEngagement = m.likes() + m.commentCount() + m.shareCount();
        if (m.readCount() == 0 && m.playCount() == 0) return 0;
        long base = Math.max(m.readCount(), m.playCount());
        return (double) totalEngagement / base;
    }

    /**
     * Compute a growth rate from parsed user summary data.
     *
     * @param m parsed metrics
     * @return growth rate as a decimal, or 0 if no data
     */
    public double computeGrowthRate(ParsedMetrics m) {
        if (m.newUsers() == 0) return 0;
        return (double) m.netGrowth() / m.newUsers();
    }

    /**
     * Compute a read completion rate as a fraction (0-1).
     *
     * @param m parsed metrics
     * @return read finish rate, or 0 if not available
     */
    public double getReadFinishRate(ParsedMetrics m) {
        // If the platform directly reports read finish rate, use it
        if (m.readFinishRate() > 0) return m.readFinishRate();
        // Otherwise estimate from collect rate as a proxy
        if (m.readCount() > 0 && m.collectCount() > 0) {
            return Math.min((double) m.collectCount() / m.readCount(), 1.0);
        }
        return 0;
    }

    // ════════════════ Extraction helpers ════════════════

    /**
     * Extract a long integer value following any of the given labels in the text.
     * Handles formats like "阅读人数: 1234", "阅读人数 1234", "阅读人数: 1,234".
     */
    private long extractLong(String text, String... labels) {
        for (String label : labels) {
            // Pattern: label followed by optional colon/space, then digits (with optional commas)
            Pattern p = Pattern.compile(Pattern.quote(label) + "[：:\\s]*([\\d,]+)");
            Matcher m = p.matcher(text);
            if (m.find()) {
                try {
                    String numStr = m.group(1).replace(",", "");
                    return Long.parseLong(numStr);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    /**
     * Extract a double value (percentage or decimal) following any of the given labels.
     * Handles formats like "阅读完成率: 45.2%", "互动率: 6.1%", "平均阅读时长: 3.5分钟".
     */
    private double extractDouble(String text, String... labels) {
        for (String label : labels) {
            // Pattern: label followed by optional colon/space, then a decimal number
            Pattern p = Pattern.compile(Pattern.quote(label) + "[：:\\s]*([\\d.]+)");
            Matcher m = p.matcher(text);
            if (m.find()) {
                try {
                    double value = Double.parseDouble(m.group(1));
                    // If the value looks like a percentage (e.g. 45.2), convert to fraction
                    // We detect this by checking if the context contains a % sign nearby
                    int endPos = m.end();
                    int checkWindow = Math.min(endPos + 3, text.length());
                    String afterMatch = text.substring(endPos, checkWindow);
                    if (afterMatch.contains("%")) {
                        return value / 100.0;
                    }
                    // For "环比" values that are typically percentages without % sign
                    if (label.equals("环比") && value > 1.0) {
                        return value / 100.0;
                    }
                    return value;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    // ════════════════ Parsed metrics record ════════════════

    /**
     * Immutable container for all parsed metrics from analysis data text.
     */
    public record ParsedMetrics(
            long readCount,        // 阅读人数 / 阅读量
            long playCount,        // 阅读次数 / 播放量
            long shareUserCount,   // 分享人数
            long shareCount,       // 分享次数 / 转发量
            long likes,            // 点赞 / 点赞数
            long commentCount,     // 评论数
            long collectCount,     // 收藏人数 / 收藏数
            long newUsers,         // 新增粉丝
            long cancelUsers,      // 取消关注
            long netGrowth,        // 净增粉丝
            double readFinishRate, // 阅读完成率 (fraction 0-1)
            double avgReadTime,    // 平均阅读时长 (minutes)
            double engagementRate, // 互动率 (fraction 0-1)
            double deliveryRate,   // 阅读送达率 (fraction 0-1)
            double completionRate, // 完播率 (fraction 0-1)
            double growthRate      // 环比增速 (fraction, can be negative)
    ) {
        /**
         * Default constructor — all metrics at zero (no data parsed).
         */
        public ParsedMetrics() {
            this(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /**
         * Check if any real data was successfully parsed.
         */
        public boolean hasData() {
            return readCount > 0 || playCount > 0 || likes > 0
                    || commentCount > 0 || shareCount > 0 || netGrowth != 0
                    || engagementRate > 0 || readFinishRate > 0;
        }

        /**
         * Check if engagement metrics (likes, comments, shares) are available.
         */
        public boolean hasEngagementData() {
            return likes > 0 || commentCount > 0 || shareCount > 0;
        }

        /**
         * Check if growth metrics (new/cancel users) are available.
         */
        public boolean hasGrowthData() {
            return newUsers > 0 || netGrowth != 0;
        }

        /**
         * Total engagement count (likes + comments + shares).
         */
        public long totalEngagement() {
            return likes + commentCount + shareCount;
        }
    }
}
