package com.contentops.common.profile.audience;

import com.contentops.common.profile.audience.AudienceProfile.BehaviorProfile;
import com.contentops.common.profile.audience.AudienceProfile.DemographicProfile;
import com.contentops.common.profile.audience.AudienceProfile.GenderDistribution;
import com.contentops.common.profile.audience.AudienceProfile.GrowthProfile;
import com.contentops.common.profile.audience.AudienceProfile.GrowthTrend;
import com.contentops.common.profile.audience.AudienceProfile.RegionStat;
import com.contentops.common.profile.audience.AudienceProfile.TagPreference;
import com.contentops.common.profile.audience.AudienceProfile.TimeSlotActivity;
import com.contentops.common.profile.audience.ContentProfile.ContentType;
import com.contentops.common.profile.audience.ContentProfile.MonetizationProfile;
import com.contentops.common.profile.audience.ContentProfile.MonetizationType;
import com.contentops.common.profile.audience.ContentProfile.PerformanceHistory;
import com.contentops.common.profile.audience.ContentProfile.PlatformFit;
import com.contentops.common.profile.audience.ContentProfile.TopicDistribution;
import com.contentops.common.profile.audience.ContentProfile.TopicKeyword;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 画像注入器（P1 用户画像扩展系统 —— Prompt 增强核心组件）。
 *
 * <p>在 AnalysisAgent 和 OptimizeAgent 调用前，将受众画像与内容画像的上下文注入到 Prompt 中，
 * 使 Agent 能基于真实的平台数据进行分析与优化，而非凭空推测。
 *
 * <h3>注入场景</h3>
 * <ul>
 *   <li>{@link #enrichAnalysisPrompt} —— 分析时注入受众画像（让 Agent 知道粉丝是谁、喜欢什么）</li>
 *   <li>{@link #enrichOptimizationPrompt} —— 优化时注入内容画像和历史表现（让 Agent 知道什么有效什么无效）</li>
 * </ul>
 *
 * <h3>摘要生成</h3>
 * <ul>
 *   <li>{@link #generateAudienceSummary} —— 生成人类可读的受众画像摘要</li>
 *   <li>{@link #generateContentSummary} —— 生成人类可读的内容画像摘要</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <p>当账号尚无受众画像或内容画像时，各注入方法原样返回原始 Prompt，不影响 Agent 正常执行。
 *
 * <h3>注入格式示例</h3>
 * <pre>
 * ## 受众画像（基于平台数据分析）
 * - 粉丝量级：12.5万，30日增长3.2%
 * - 性别分布：女性62%，男性35%
 * - 地域TOP3：广东(18%)、北京(12%)、上海(10%)
 * - 活跃时段：20:00-22:00（互动率最高6.8%）
 * - 内容偏好：个人成长(38%)、干货教程(25%)、观点输出(20%)
 * 请基于以上受众画像分析内容表现。
 * </pre>
 *
 * @see AudienceProfileService
 * @see AudienceProfile
 * @see ContentProfile
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileEnricher {

    private final AudienceProfileService profileService;

    // ════════════════════════════════════════════════════════════════
    // Prompt 注入
    // ════════════════════════════════════════════════════════════════

    /**
     * 分析时注入受众画像，让 Agent 知道粉丝是谁、喜欢什么。
     *
     * <p>将受众的人口属性、行为偏好与增长态势注入到分析 Prompt 中，
     * 使 DataAnalysisAgent 能结合受众画像分析内容表现。
     *
     * @param accountId 账号 ID
     * @param prompt    原始分析 Prompt
     * @return 注入受众画像上下文后的 Prompt；无画像时原样返回
     */
    public String enrichAnalysisPrompt(String accountId, String prompt) {
        AudienceProfile audience = profileService.getProfile(accountId);
        if (audience == null || !audience.hasData()) {
            log.debug("[ProfileEnricher] 账号无受众画像，分析 Prompt 不注入: accountId={}", accountId);
            return prompt;
        }
        String summary = generateAudienceSummary(audience);
        String block = summary + "\n请基于以上受众画像分析内容表现。";
        log.debug("[ProfileEnricher] 已为分析注入受众画像: accountId={}", accountId);
        return appendBlock(prompt, block);
    }

    /**
     * 优化时注入内容画像和历史表现，让 Agent 知道什么有效什么无效。
     *
     * <p>将内容画像的选题分布、历史表现（高/低表现特征）与变现画像注入到优化 Prompt 中，
     * 使 OptimizationAgent 能基于历史数据调整运营策略。
     *
     * @param accountId 账号 ID
     * @param prompt    原始优化 Prompt
     * @return 注入内容画像与历史表现后的 Prompt；无画像时原样返回
     */
    public String enrichOptimizationPrompt(String accountId, String prompt) {
        ContentProfile content = profileService.getContentProfile(accountId);
        if (content == null || !content.hasData()) {
            log.debug("[ProfileEnricher] 账号无内容画像，优化 Prompt 不注入: accountId={}", accountId);
            return prompt;
        }
        String summary = generateContentSummary(content);
        String block = summary + "\n请基于以上内容画像和历史表现优化运营策略。";
        log.debug("[ProfileEnricher] 已为优化注入内容画像: accountId={}", accountId);
        return appendBlock(prompt, block);
    }

    // ════════════════════════════════════════════════════════════════
    // 摘要生成
    // ════════════════════════════════════════════════════════════════

    /**
     * 生成人类可读的受众画像摘要。
     *
     * <p>将三层结构化受众画像转为自然语言摘要，包含粉丝量级、性别分布、地域 TOP3、
     * 活跃时段、内容偏好等关键信息。
     *
     * @param audienceProfile 受众画像
     * @return 人类可读的受众画像摘要文本；画像为 null 时返回占位文本
     */
    public String generateAudienceSummary(AudienceProfile audienceProfile) {
        if (audienceProfile == null) {
            return "## 受众画像\n（暂无受众画像数据）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 受众画像（基于平台数据分析）\n");

        // 人口属性
        DemographicProfile demo = audienceProfile.demographic();
        if (demo.followerCount() > 0) {
            sb.append("- 粉丝量级：").append(formatCount(demo.followerCount()));
            GrowthProfile growth = audienceProfile.growth();
            if (growth.followerGrowthRate() != 0) {
                sb.append("，30日增长").append(formatPercent(growth.followerGrowthRate()));
            }
            sb.append('\n');
        }

        GenderDistribution gender = demo.genderDistribution();
        if (gender.hasData()) {
            sb.append("- 性别分布：女性").append(formatPercent(gender.femaleRatio()))
                    .append("，男性").append(formatPercent(gender.maleRatio()));
            if (gender.unknownRatio() > 0) {
                sb.append("，未知").append(formatPercent(gender.unknownRatio()));
            }
            sb.append('\n');
        }

        if (!demo.regions().isEmpty()) {
            sb.append("- 地域TOP").append(Math.min(3, demo.regions().size())).append("：");
            int limit = Math.min(3, demo.regions().size());
            for (int i = 0; i < limit; i++) {
                RegionStat r = demo.regions().get(i);
                if (i > 0) sb.append("、");
                sb.append(r.region()).append("(").append(formatPercent(r.ratio())).append(")");
            }
            sb.append('\n');
        }

        // 行为偏好
        BehaviorProfile behavior = audienceProfile.behavior();
        if (!behavior.activeTimeSlots().isEmpty()) {
            TimeSlotActivity topSlot = behavior.activeTimeSlots().get(0);
            sb.append("- 活跃时段：").append(topSlot.timeSlot());
            if (topSlot.avgEngagementRate() > 0) {
                sb.append("（互动率最高").append(formatPercent(topSlot.avgEngagementRate())).append("）");
            }
            sb.append('\n');
        }

        if (!behavior.tagPreferences().isEmpty()) {
            sb.append("- 内容偏好：");
            int limit = Math.min(3, behavior.tagPreferences().size());
            for (int i = 0; i < limit; i++) {
                TagPreference t = behavior.tagPreferences().get(i);
                if (i > 0) sb.append("、");
                sb.append(t.tag()).append("(").append(formatPercent(t.weight())).append(")");
            }
            sb.append('\n');
        }

        // 增长态势
        GrowthProfile growth = audienceProfile.growth();
        if (growth.growthTrend() != GrowthTrend.STEADY || growth.netGrowth30d() != 0) {
            sb.append("- 增长趋势：").append(growth.growthTrend().label());
            if (growth.netGrowth30d() != 0) {
                sb.append("，30日净增").append(growth.netGrowth30d());
            }
            sb.append('\n');
        }

        return sb.toString().stripTrailing();
    }

    /**
     * 生成人类可读的内容画像摘要。
     *
     * <p>将三层结构化内容画像转为自然语言摘要，包含选题关键词、内容类型配比、
     * 历史表现、高/低表现特征与变现信息。
     *
     * @param contentProfile 内容画像
     * @return 人类可读的内容画像摘要文本；画像为 null 时返回占位文本
     */
    public String generateContentSummary(ContentProfile contentProfile) {
        if (contentProfile == null) {
            return "## 内容画像\n（暂无内容画像数据）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 内容画像（基于历史内容分析）\n");

        // 选题分布
        TopicDistribution topic = contentProfile.topicDistribution();
        if (!topic.topicKeywords().isEmpty()) {
            sb.append("- 选题关键词：");
            int limit = Math.min(5, topic.topicKeywords().size());
            for (int i = 0; i < limit; i++) {
                TopicKeyword kw = topic.topicKeywords().get(i);
                if (i > 0) sb.append("、");
                sb.append(kw.keyword());
            }
            sb.append('\n');
        }

        if (!topic.contentTypeRatio().isEmpty()) {
            sb.append("- 内容类型配比：");
            List<Map.Entry<ContentType, Double>> sorted = topic.contentTypeRatio().entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .toList();
            int limit = Math.min(3, sorted.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<ContentType, Double> entry = sorted.get(i);
                if (i > 0) sb.append("、");
                sb.append(entry.getKey().label()).append("(").append(formatPercent(entry.getValue())).append(")");
            }
            sb.append('\n');
        }

        // 历史表现
        PerformanceHistory perf = contentProfile.performanceHistory();
        if (perf.avgEngagementRate() > 0 || perf.avgReadCount() > 0) {
            sb.append("- 历史表现：");
            if (perf.avgReadCount() > 0) {
                sb.append("平均阅读").append(formatCount(perf.avgReadCount()));
            }
            if (perf.avgEngagementRate() > 0) {
                sb.append("，互动率").append(formatPercent(perf.avgEngagementRate()));
            }
            if (!perf.bestPublishTimeSlot().equals("未知")) {
                sb.append("，最佳时段").append(perf.bestPublishTimeSlot());
            }
            sb.append('\n');
        }

        if (!perf.highPerformanceTraits().isEmpty()) {
            sb.append("- 高表现特征：").append(String.join("、", perf.highPerformanceTraits())).append('\n');
        }
        if (!perf.lowPerformanceCauses().isEmpty()) {
            sb.append("- 低表现归因：").append(String.join("、", perf.lowPerformanceCauses())).append('\n');
        }

        // 变现画像
        MonetizationProfile monetization = contentProfile.monetization();
        if (!monetization.monetizationTypes().isEmpty()) {
            sb.append("- 变现方式：");
            int limit = Math.min(3, monetization.monetizationTypes().size());
            for (int i = 0; i < limit; i++) {
                MonetizationType type = monetization.monetizationTypes().get(i);
                if (i > 0) sb.append("、");
                sb.append(type.label());
            }
            if (monetization.commercializationRate() > 0) {
                sb.append("，商业化率").append(formatPercent(monetization.commercializationRate()));
            }
            sb.append('\n');
        }

        // 平台适配度
        if (!topic.platformFits().isEmpty()) {
            sb.append("- 平台表现：");
            int limit = Math.min(3, topic.platformFits().size());
            for (int i = 0; i < limit; i++) {
                PlatformFit fit = topic.platformFits().get(i);
                if (i > 0) sb.append("、");
                sb.append(fit.platform()).append("(阅读").append(formatCount(fit.avgReadCount()))
                        .append("，互动").append(formatPercent(fit.avgEngagementRate())).append(")");
            }
            sb.append('\n');
        }

        return sb.toString().stripTrailing();
    }

    // ════════════════════════════════════════════════════════════════
    // 内部方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 将画像块追加到原始 Prompt 之后。
     *
     * @param prompt 原始 Prompt
     * @param block  画像块文本
     * @return 追加后的 Prompt
     */
    private String appendBlock(String prompt, String block) {
        if (prompt == null || prompt.isBlank()) {
            return block;
        }
        return prompt + "\n\n" + block;
    }

    /**
     * 将小数格式化为百分比字符串（保留一位小数）。
     *
     * @param value 小数（如 0.068）
     * @return 百分比字符串（如 "6.8%"）
     */
    private String formatPercent(double value) {
        return String.format("%.1f%%", value * 100);
    }

    /**
     * 将粉丝量级格式化为可读字符串（万级以上用「万」单位）。
     *
     * @param count 粉丝数
     * @return 可读字符串（如 "12.5万"）
     */
    private String formatCount(long count) {
        if (count >= 10000) {
            return String.format("%.1f万", count / 10000.0);
        }
        return String.valueOf(count);
    }
}
