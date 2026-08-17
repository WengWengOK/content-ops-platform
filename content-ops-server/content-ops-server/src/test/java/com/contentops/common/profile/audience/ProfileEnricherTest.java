package com.contentops.common.profile.audience;

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
import com.contentops.common.profile.audience.ContentProfile.MonetizationProfile;
import com.contentops.common.profile.audience.ContentProfile.MonetizationType;
import com.contentops.common.profile.audience.ContentProfile.PerformanceHistory;
import com.contentops.common.profile.audience.ContentProfile.PlatformFit;
import com.contentops.common.profile.audience.ContentProfile.TopicDistribution;
import com.contentops.common.profile.audience.ContentProfile.TopicKeyword;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * ProfileEnricher 单元测试。
 *
 * <p>验证画像注入器在以下场景的正确行为：
 * <ul>
 *   <li>有受众画像时注入分析 Prompt</li>
 *   <li>无受众画像时优雅降级（原样返回 Prompt）</li>
 *   <li>有内容画像时注入优化 Prompt</li>
 *   <li>无内容画像时优雅降级</li>
 *   <li>受众画像摘要生成（包含粉丝量级、性别、地域、时段、偏好等）</li>
 *   <li>内容画像摘要生成（包含选题、类型配比、历史表现、变现等）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileEnricher 画像注入器测试")
class ProfileEnricherTest {

    @Mock
    private AudienceProfileService profileService;

    private ProfileEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new ProfileEnricher(profileService);
    }

    // ════════════════ enrichAnalysisPrompt ════════════════

    @Nested
    @DisplayName("enrichAnalysisPrompt 分析 Prompt 注入")
    class EnrichAnalysisPromptTest {

        @Test
        @DisplayName("有受众画像时应注入画像摘要到 Prompt")
        void shouldEnrichPromptWhenProfileExists() {
            String accountId = "acc-001";
            AudienceProfile profile = buildFullAudienceProfile(accountId);
            when(profileService.getProfile(accountId)).thenReturn(profile);

            String originalPrompt = "请分析以下运营数据";
            String result = enricher.enrichAnalysisPrompt(accountId, originalPrompt);

            assertNotNull(result);
            assertTrue(result.contains(originalPrompt), "应保留原始 Prompt");
            assertTrue(result.contains("受众画像"), "应包含受众画像标题");
            assertTrue(result.contains("粉丝量级"), "应包含粉丝量级");
            assertTrue(result.contains("性别分布"), "应包含性别分布");
            assertTrue(result.contains("请基于以上受众画像分析内容表现"), "应包含分析引导语");
        }

        @Test
        @DisplayName("无受众画像时应原样返回 Prompt（优雅降级）")
        void shouldReturnOriginalPromptWhenNoProfile() {
            String accountId = "acc-002";
            when(profileService.getProfile(accountId)).thenReturn(null);

            String originalPrompt = "请分析以下运营数据";
            String result = enricher.enrichAnalysisPrompt(accountId, originalPrompt);

            assertEquals(originalPrompt, result, "无画像时应原样返回");
        }

        @Test
        @DisplayName("画像无有效数据时应原样返回 Prompt")
        void shouldReturnOriginalPromptWhenProfileHasNoData() {
            String accountId = "acc-003";
            AudienceProfile emptyProfile = AudienceProfile.empty(accountId);
            when(profileService.getProfile(accountId)).thenReturn(emptyProfile);

            String originalPrompt = "请分析以下运营数据";
            String result = enricher.enrichAnalysisPrompt(accountId, originalPrompt);

            assertEquals(originalPrompt, result, "空画像时应原样返回");
        }
    }

    // ════════════════ enrichOptimizationPrompt ════════════════

    @Nested
    @DisplayName("enrichOptimizationPrompt 优化 Prompt 注入")
    class EnrichOptimizationPromptTest {

        @Test
        @DisplayName("有内容画像时应注入画像摘要到 Prompt")
        void shouldEnrichPromptWhenContentProfileExists() {
            String accountId = "acc-001";
            ContentProfile content = buildFullContentProfile(accountId);
            when(profileService.getContentProfile(accountId)).thenReturn(content);

            String originalPrompt = "请优化运营策略";
            String result = enricher.enrichOptimizationPrompt(accountId, originalPrompt);

            assertNotNull(result);
            assertTrue(result.contains(originalPrompt), "应保留原始 Prompt");
            assertTrue(result.contains("内容画像"), "应包含内容画像标题");
            assertTrue(result.contains("选题关键词"), "应包含选题关键词");
            assertTrue(result.contains("历史表现"), "应包含历史表现");
            assertTrue(result.contains("请基于以上内容画像和历史表现优化运营策略"), "应包含优化引导语");
        }

        @Test
        @DisplayName("无内容画像时应原样返回 Prompt（优雅降级）")
        void shouldReturnOriginalPromptWhenNoContentProfile() {
            String accountId = "acc-002";
            when(profileService.getContentProfile(accountId)).thenReturn(null);

            String originalPrompt = "请优化运营策略";
            String result = enricher.enrichOptimizationPrompt(accountId, originalPrompt);

            assertEquals(originalPrompt, result, "无画像时应原样返回");
        }
    }

    // ════════════════ generateAudienceSummary ════════════════

    @Nested
    @DisplayName("generateAudienceSummary 受众画像摘要")
    class GenerateAudienceSummaryTest {

        @Test
        @DisplayName("应生成包含所有维度的受众画像摘要")
        void shouldGenerateFullSummary() {
            AudienceProfile profile = buildFullAudienceProfile("acc-001");
            String summary = enricher.generateAudienceSummary(profile);

            assertNotNull(summary);
            assertTrue(summary.contains("受众画像"), "应包含标题");
            // 人口属性
            assertTrue(summary.contains("粉丝量级"), "应包含粉丝量级");
            assertTrue(summary.contains("12.5万"), "应格式化粉丝量级");
            assertTrue(summary.contains("30日增长"), "应包含增长率");
            assertTrue(summary.contains("性别分布"), "应包含性别分布");
            assertTrue(summary.contains("女性"), "应包含女性占比");
            assertTrue(summary.contains("地域TOP"), "应包含地域分布");
            assertTrue(summary.contains("广东"), "应包含地域名");
            // 行为偏好
            assertTrue(summary.contains("活跃时段"), "应包含活跃时段");
            assertTrue(summary.contains("20:00-22:00"), "应包含时段值");
            assertTrue(summary.contains("内容偏好"), "应包含内容偏好");
            // 增长态势
            assertTrue(summary.contains("增长趋势"), "应包含增长趋势");
        }

        @Test
        @DisplayName("空画像应返回占位文本")
        void shouldReturnPlaceholderForNullProfile() {
            String summary = enricher.generateAudienceSummary(null);
            assertNotNull(summary);
            assertTrue(summary.contains("暂无受众画像数据"));
        }

        @Test
        @DisplayName("仅有人口属性的画像应只输出人口属性部分")
        void shouldOutputOnlyDemographicForPartialProfile() {
            AudienceProfile profile = new AudienceProfile(
                    "acc-004",
                    new DemographicProfile(5000, new GenderDistribution(0.4, 0.55, 0.05),
                            List.of(), new AgeDistribution(Map.of())),
                    BehaviorProfile.empty(),
                    GrowthProfile.empty(),
                    null, null
            );
            String summary = enricher.generateAudienceSummary(profile);

            assertNotNull(summary);
            assertTrue(summary.contains("粉丝量级"));
            assertFalse(summary.contains("活跃时段"), "无行为数据时不应包含活跃时段");
        }
    }

    // ════════════════ generateContentSummary ════════════════

    @Nested
    @DisplayName("generateContentSummary 内容画像摘要")
    class GenerateContentSummaryTest {

        @Test
        @DisplayName("应生成包含所有维度的内容画像摘要")
        void shouldGenerateFullSummary() {
            ContentProfile content = buildFullContentProfile("acc-001");
            String summary = enricher.generateContentSummary(content);

            assertNotNull(summary);
            assertTrue(summary.contains("内容画像"), "应包含标题");
            assertTrue(summary.contains("选题关键词"), "应包含选题关键词");
            assertTrue(summary.contains("内容类型配比"), "应包含内容类型配比");
            assertTrue(summary.contains("历史表现"), "应包含历史表现");
            assertTrue(summary.contains("高表现特征"), "应包含高表现特征");
            assertTrue(summary.contains("变现方式"), "应包含变现方式");
        }

        @Test
        @DisplayName("空画像应返回占位文本")
        void shouldReturnPlaceholderForNullContentProfile() {
            String summary = enricher.generateContentSummary(null);
            assertNotNull(summary);
            assertTrue(summary.contains("暂无内容画像数据"));
        }
    }

    // ════════════════ 测试辅助 ════════════════

    /** 构建包含完整三层数据的受众画像。 */
    private AudienceProfile buildFullAudienceProfile(String accountId) {
        DemographicProfile demographic = new DemographicProfile(
                125000,
                new GenderDistribution(0.35, 0.62, 0.03),
                List.of(
                        new RegionStat("广东", 0.18, 22500),
                        new RegionStat("北京", 0.12, 15000),
                        new RegionStat("上海", 0.10, 12500)
                ),
                new AgeDistribution(Map.of(
                        "18-24", 0.35,
                        "25-30", 0.30,
                        "31-40", 0.25,
                        "40+", 0.10
                ))
        );

        BehaviorProfile behavior = new BehaviorProfile(
                List.of(
                        new TimeSlotActivity("20:00-22:00", 1.0, 0.068),
                        new TimeSlotActivity("12:00-13:00", 0.7, 0.045),
                        new TimeSlotActivity("08:00-09:00", 0.5, 0.032)
                ),
                List.of(
                        new TagPreference("个人成长", 0.38, 12),
                        new TagPreference("干货教程", 0.25, 8),
                        new TagPreference("观点输出", 0.20, 5)
                ),
                new InteractionTendency(0.15, 0.25, 0.20, 0.40)
        );

        GrowthProfile growth = new GrowthProfile(
                350, 1500, 0.032,
                Map.of("搜索", 0.3, "推荐", 0.5, "分享", 0.2),
                GrowthTrend.STEADY
        );

        return new AudienceProfile(accountId, demographic, behavior, growth, null, null);
    }

    /** 构建包含完整三层数据的内容画像。 */
    private ContentProfile buildFullContentProfile(String accountId) {
        TopicDistribution topic = new TopicDistribution(
                List.of(
                        new TopicKeyword("涨粉技巧", 5, LocalDate.now(), 0.7),
                        new TopicKeyword("成长故事", 3, LocalDate.now(), 0.5)
                ),
                Map.of(
                        ContentType.TUTORIAL, 0.35,
                        ContentType.PERSONAL_STORY, 0.25,
                        ContentType.OPINION, 0.20
                ),
                List.of(new PlatformFit("公众号", 8000, 0.06, 20))
        );

        PerformanceHistory performance = new PerformanceHistory(
                0.06, 8000, "20:00-22:00",
                List.of("高收藏率内容受用户认可", "高分享率内容具传播性"),
                List.of("阅读完成率偏低")
        );

        MonetizationProfile monetization = new MonetizationProfile(
                List.of(MonetizationType.AD_REVENUE, MonetizationType.BRAND_COLLAB),
                List.of(),
                0.15
        );

        return new ContentProfile(accountId, topic, performance, monetization, null, null);
    }
}
