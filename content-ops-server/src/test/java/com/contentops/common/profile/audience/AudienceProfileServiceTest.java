package com.contentops.common.profile.audience;

import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.platform.MetricsParser;
import com.contentops.common.profile.audience.AudienceProfile.BehaviorProfile;
import com.contentops.common.profile.audience.AudienceProfile.DemographicProfile;
import com.contentops.common.profile.audience.AudienceProfile.GrowthProfile;
import com.contentops.common.profile.audience.AudienceProfile.RegionStat;
import com.contentops.common.profile.audience.AudienceProfile.TagPreference;
import com.contentops.common.profile.audience.AudienceProfile.TimeSlotActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * AudienceProfileService 单元测试。
 *
 * <p>验证 P1 优化后从平台文本中真实提取数据的能力：
 * <ul>
 *   <li>地域分布提取（从"地域分布: 广东(18%) 北京(12%)"格式中解析）</li>
 *   <li>活跃时段提取（从"发布时间: 2024-01-15 21:00:00"格式中解析并分组）</li>
 *   <li>内容偏好标签提取（从标题关键词分类提取）</li>
 *   <li>降级策略（无数据时返回空列表或默认值）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AudienceProfileService 真实数据提取测试")
class AudienceProfileServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private AudienceProfileService profileService;
    private final AudienceProfileProperties properties = new AudienceProfileProperties();

    @BeforeEach
    void setUp() {
        MetricsParser metricsParser = new MetricsParser();
        profileService = new AudienceProfileService(knowledgeBaseService, properties, metricsParser);
    }

    /** 包含地域分布的微信数据。 */
    private static final String DATA_WITH_REGIONS =
            "[微信用户数据] 日期: 2024-01-15\n"
                    + "地域分布: 广东(18%) 北京(12%) 上海(10%) 浙江(8%) 江苏(6%)\n"
                    + "新增: 100, 取消: 10\n"
                    + "净增粉丝 90\n";

    /** 包含发布时间与互动指标的多标题数据。 */
    private static final String DATA_WITH_TIMES =
            "[内容数据]\n"
                    + "- 标题: 干货教程：涨粉技巧\n"
                    + "  发布时间: 2024-01-15 08:30:00\n"
                    + "  阅读人数: 5000, 点赞: 600, 评论数: 80, 分享次数: 40, 收藏人数: 100\n"
                    + "- 标题: 我的成长故事\n"
                    + "  发布时间: 2024-01-15 12:30:00\n"
                    + "  阅读人数: 3000, 点赞: 500, 评论数: 100, 分享次数: 60, 收藏人数: 150\n"
                    + "- 标题: 热点解读：最新事件\n"
                    + "  发布时间: 2024-01-15 21:00:00\n"
                    + "  阅读人数: 8000, 点赞: 400, 评论数: 50, 分享次数: 20, 收藏人数: 80\n"
                    + "新增: 200, 取消: 20, 净增粉丝: 180\n";

    /** 无任何地域/时间信息的纯指标数据。 */
    private static final String DATA_PLAIN_METRICS =
            "阅读人数: 1000, 点赞: 200, 评论数: 50, 分享: 100, 收藏: 80\n"
                    + "新增: 100, 取消: 10, 净增粉丝: 90\n";

    // ════════════════ 地域提取 ════════════════

    @Nested
    @DisplayName("地域分布提取")
    class RegionExtractionTest {

        @Test
        @DisplayName("应从地域分布文本中提取真实地域数据")
        void shouldExtractRegionsFromText() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-001", DATA_WITH_REGIONS);

            DemographicProfile demo = profile.demographic();
            assertNotNull(demo.regions());
            assertFalse(demo.regions().isEmpty(), "应提取到地域数据");

            RegionStat first = demo.regions().get(0);
            assertEquals("广东", first.region(), "第一个地域应为广东");
            assertEquals(0.18, first.ratio(), 0.001, "广东占比应为18%");
            assertTrue(first.count() > 0, "应有估算粉丝数");
        }

        @Test
        @DisplayName("无地域数据时应返回空列表")
        void shouldReturnEmptyWhenNoRegionData() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-002", DATA_PLAIN_METRICS);

            assertTrue(profile.demographic().regions().isEmpty(), "无地域数据时应返回空列表");
        }
    }

    // ════════════════ 时段提取 ════════════════

    @Nested
    @DisplayName("活跃时段提取")
    class TimeSlotExtractionTest {

        @Test
        @DisplayName("应从发布时间中提取真实时段分布")
        void shouldExtractTimeSlotsFromPublishTimes() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-001", DATA_WITH_TIMES);

            BehaviorProfile behavior = profile.behavior();
            assertNotNull(behavior.activeTimeSlots());
            assertFalse(behavior.activeTimeSlots().isEmpty(), "应提取到时段数据");

            // 应包含至少一个黄金时段
            boolean hasGoldenHour = behavior.activeTimeSlots().stream()
                    .anyMatch(s -> s.timeSlot().contains("20:00") || s.timeSlot().contains("12:00"));
            assertTrue(hasGoldenHour, "应包含黄金时段");
        }

        @Test
        @DisplayName("无时间数据时应降级返回默认时段")
        void shouldFallbackToDefaultWhenNoTimeData() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-002", DATA_PLAIN_METRICS);

            BehaviorProfile behavior = profile.behavior();
            assertNotNull(behavior.activeTimeSlots());
            assertFalse(behavior.activeTimeSlots().isEmpty(), "即使无数据也应有降级时段");
        }

        @Test
        @DisplayName("时段应包含互动率数据")
        void shouldIncludeEngagementRateInTimeSlots() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-001", DATA_WITH_TIMES);

            for (TimeSlotActivity slot : profile.behavior().activeTimeSlots()) {
                assertTrue(slot.avgEngagementRate() >= 0, "互动率应非负");
            }
        }
    }

    // ════════════════ 标签提取 ════════════════

    @Nested
    @DisplayName("内容偏好标签提取")
    class TagPreferenceExtractionTest {

        @Test
        @DisplayName("应从标题关键词中提取内容偏好标签")
        void shouldExtractTagPreferencesFromTitles() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-001", DATA_WITH_TIMES);

            BehaviorProfile behavior = profile.behavior();
            assertNotNull(behavior.tagPreferences());
            assertFalse(behavior.tagPreferences().isEmpty(), "应提取到标签偏好");

            // 应包含从标题分类得到的标签
            boolean hasTutorialTag = behavior.tagPreferences().stream()
                    .anyMatch(t -> t.tag().contains("干货") || t.tag().contains("教程"));
            assertTrue(hasTutorialTag, "应包含干货教程标签");

            boolean hasStoryTag = behavior.tagPreferences().stream()
                    .anyMatch(t -> t.tag().contains("故事"));
            assertTrue(hasStoryTag, "应包含个人故事标签");
        }

        @Test
        @DisplayName("标签权重应归一化（总和接近1）")
        void shouldNormalizeTagWeights() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-001", DATA_WITH_TIMES);

            List<TagPreference> tags = profile.behavior().tagPreferences();
            if (!tags.isEmpty()) {
                double sum = tags.stream().mapToDouble(TagPreference::weight).sum();
                assertEquals(1.0, sum, 0.01, "标签权重总和应接近1");
            }
        }

        @Test
        @DisplayName("无标题数据时应返回空标签列表")
        void shouldReturnEmptyTagsWhenNoTitles() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-002", DATA_PLAIN_METRICS);

            assertTrue(profile.behavior().tagPreferences().isEmpty(), "无标题时应返回空标签列表");
        }
    }

    // ════════════════ 增长态势 ════════════════

    @Nested
    @DisplayName("增长态势画像")
    class GrowthProfileTest {

        @Test
        @DisplayName("应从指标中提取粉丝增长数据")
        void shouldExtractGrowthData() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-001", DATA_WITH_REGIONS);

            GrowthProfile growth = profile.growth();
            assertTrue(growth.netGrowth7d() > 0, "应有7日净增数据");
            assertTrue(growth.netGrowth30d() > 0, "应有30日净增估算");
            assertTrue(growth.followerGrowthRate() > 0, "应有增长率");
        }

        @Test
        @DisplayName("无增长数据时增长态势应为空")
        void shouldReturnEmptyGrowthWhenNoData() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-002", "无任何数据的文本");

            GrowthProfile growth = profile.growth();
            assertEquals(0, growth.netGrowth7d(), "无数据时7日净增应为0");
            assertEquals(0, growth.netGrowth30d(), "无数据时30日净增应为0");
        }
    }

    // ════════════════ 整体画像 ════════════════

    @Nested
    @DisplayName("整体画像构建")
    class FullProfileTest {

        @Test
        @DisplayName("hasData应在提取到真实数据时返回true")
        void shouldReturnTrueHasDataWhenRealDataExtracted() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-001", DATA_WITH_TIMES);

            assertTrue(profile.hasData(), "提取到真实数据后 hasData 应为 true");
        }

        @Test
        @DisplayName("空输入应构建空画像")
        void shouldBuildEmptyProfileForEmptyInput() {
            when(knowledgeBaseService.isAvailable()).thenReturn(false);

            AudienceProfile profile = profileService.buildProfileFromMetrics("acc-003", "");

            assertFalse(profile.hasData(), "空输入应构建空画像");
        }
    }
}
