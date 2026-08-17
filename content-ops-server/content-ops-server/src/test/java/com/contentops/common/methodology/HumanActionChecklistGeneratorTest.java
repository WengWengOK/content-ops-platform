package com.contentops.common.methodology;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.platform.MetricsParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HumanActionChecklistGenerator 单元测试（P2 优化: 基于真实数据生成动态检查项）。
 *
 * <p>验证人工行动清单生成器的核心行为：
 * <ul>
 *   <li>默认清单覆盖所有阶段，每阶段 4 项</li>
 *   <li>功能关闭返回空列表</li>
 *   <li>DATA_ANALYSIS 阶段基于 MetricsParser 检测低互动率、负增长等异常</li>
 *   <li>TOPIC_PLANNING 空值检查 bug 修复验证</li>
 *   <li>CONTENT_CREATION / PUBLISHING / OPTIMIZATION / IMAGE_DESIGN 阶段动态检查项</li>
 *   <li>配置覆盖默认清单</li>
 * </ul>
 */
@DisplayName("HumanActionChecklistGenerator 测试")
class HumanActionChecklistGeneratorTest {

    private ChecklistProperties properties;
    private MetricsParser metricsParser;
    private HumanActionChecklistGenerator generator;

    @BeforeEach
    void setUp() {
        properties = new ChecklistProperties();
        metricsParser = new MetricsParser();
        generator = new HumanActionChecklistGenerator(properties, metricsParser);
    }

    // ════════════════ 默认清单 ════════════════

    @Nested
    @DisplayName("defaultChecklist — 内置默认清单")
    class DefaultChecklist {

        @Test
        @DisplayName("每个阶段应返回 4 项默认检查项")
        void eachStage_shouldReturn4Items() {
            for (AgentStage stage : AgentStage.values()) {
                List<String> checklist = generator.defaultChecklist(stage);
                assertEquals(4, checklist.size(),
                        stage.getCode() + " 应有 4 项默认检查项");
            }
        }

        @Test
        @DisplayName("null 阶段应返回空列表")
        void nullStage_shouldReturnEmpty() {
            List<String> checklist = generator.defaultChecklist(null);
            assertTrue(checklist.isEmpty());
        }

        @Test
        @DisplayName("DATA_ANALYSIS 默认清单应包含趋势复核项")
        void dataAnalysis_shouldIncludeTrendReview() {
            List<String> checklist = generator.defaultChecklist(AgentStage.DATA_ANALYSIS);
            boolean hasTrendItem = checklist.stream()
                    .anyMatch(s -> s.contains("趋势") || s.contains("单篇"));
            assertTrue(hasTrendItem, "DATA_ANALYSIS 默认清单应包含趋势复核项");
        }

        @Test
        @DisplayName("CONTENT_CREATION 默认清单应包含事实核查项")
        void contentCreation_shouldIncludeFactCheck() {
            List<String> checklist = generator.defaultChecklist(AgentStage.CONTENT_CREATION);
            boolean hasFactCheck = checklist.stream()
                    .anyMatch(s -> s.contains("事实") || s.contains("核查"));
            assertTrue(hasFactCheck, "CONTENT_CREATION 默认清单应包含事实核查项");
        }
    }

    // ════════════════ generateChecklist 基础 ════════════════

    @Nested
    @DisplayName("generateChecklist — 生成清单")
    class GenerateChecklist {

        @Test
        @DisplayName("功能关闭时应返回空列表")
        void whenDisabled_shouldReturnEmpty() {
            properties.setEnabled(false);
            List<String> checklist = generator.generateChecklist(AgentStage.TOPIC_PLANNING, new HashMap<>());
            assertTrue(checklist.isEmpty());
        }

        @Test
        @DisplayName("null stage 应返回空列表")
        void nullStage_shouldReturnEmpty() {
            List<String> checklist = generator.generateChecklist(null, new HashMap<>());
            assertTrue(checklist.isEmpty());
        }

        @Test
        @DisplayName("启用且 outputs 为空时应返回默认清单")
        void enabledWithEmptyOutputs_shouldReturnDefaults() {
            List<String> checklist = generator.generateChecklist(AgentStage.TOPIC_PLANNING, new HashMap<>());
            assertEquals(4, checklist.size(), "空 outputs 应返回 4 项默认清单");
        }

        @Test
        @DisplayName("配置自定义检查项时应覆盖默认清单")
        void configuredItems_shouldOverrideDefaults() {
            List<String> customItems = List.of("自定义检查项1", "自定义检查项2");
            properties.setStageItems(Map.of("TOPIC_PLANNING", customItems));

            List<String> checklist = generator.generateChecklist(AgentStage.TOPIC_PLANNING, new HashMap<>());

            assertTrue(checklist.contains("自定义检查项1"));
            assertTrue(checklist.contains("自定义检查项2"));
            assertEquals(2, checklist.size(), "应仅包含配置的 2 项（无 outputs 时无动态项）");
        }
    }

    // ════════════════ DATA_ANALYSIS 动态检查项 ════════════════

    @Nested
    @DisplayName("DATA_ANALYSIS 动态检查项")
    class DataAnalysisDynamic {

        @Test
        @DisplayName("低互动率指标应生成互动率预警检查项")
        void lowEngagementRate_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("report", "阅读人数: 10000\n点赞: 100\n评论数: 50\n分享次数: 30\n互动率: 1.8%");

            List<String> checklist = generator.generateChecklist(AgentStage.DATA_ANALYSIS, outputs);

            boolean hasEngagementWarning = checklist.stream()
                    .anyMatch(s -> s.contains("互动率") && s.contains("动态"));
            assertTrue(hasEngagementWarning, "低互动率应生成动态预警检查项");
        }

        @Test
        @DisplayName("负净增粉丝应生成粉丝流失检查项")
        void negativeNetGrowth_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("report", "新增: 50\n取消: 120\n净增粉丝: -70");

            List<String> checklist = generator.generateChecklist(AgentStage.DATA_ANALYSIS, outputs);

            boolean hasFanLoss = checklist.stream()
                    .anyMatch(s -> s.contains("粉丝净增长为负") || s.contains("取关"));
            assertTrue(hasFanLoss, "负净增粉丝应生成挽留策略检查项");
        }

        @Test
        @DisplayName("低阅读完成率应生成优化建议检查项")
        void lowFinishRate_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("report", "阅读人数: 5000\n阅读完成率: 25%\n点赞: 200");

            List<String> checklist = generator.generateChecklist(AgentStage.DATA_ANALYSIS, outputs);

            boolean hasFinishRateWarning = checklist.stream()
                    .anyMatch(s -> s.contains("阅读完成率") && s.contains("动态"));
            assertTrue(hasFinishRateWarning, "低阅读完成率应生成动态检查项");
        }

        @Test
        @DisplayName("环比下降应生成应对策略检查项")
        void negativeGrowthRate_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("report", "阅读人数: 8000\n环比: -12.5%");

            List<String> checklist = generator.generateChecklist(AgentStage.DATA_ANALYSIS, outputs);

            boolean hasDeclineWarning = checklist.stream()
                    .anyMatch(s -> s.contains("环比下降") && s.contains("动态"));
            assertTrue(hasDeclineWarning, "环比下降应生成动态检查项");
        }

        @Test
        @DisplayName("内容较长但无数值指标应生成数据来源核对检查项")
        void longTextWithoutMetrics_shouldSuggestDataCheck() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("report", "本月数据整体表现良好，用户互动稳步增长，内容产出保持节奏。" +
                    "各项指标均呈现积极态势，用户留存率有所提升，内容传播效果显著。" +
                    "建议继续优化内容策略，加强用户互动引导，提升内容质量。" +
                    "同时关注用户反馈，持续迭代内容方向，确保内容与受众需求高度匹配。" +
                    "下一步计划包括扩展内容品类、优化发布节奏、深化用户运营策略等多个方面。");

            List<String> checklist = generator.generateChecklist(AgentStage.DATA_ANALYSIS, outputs);

            boolean hasDataCheck = checklist.stream()
                    .anyMatch(s -> s.contains("数值") && s.contains("数据来源") && s.contains("动态"));
            assertTrue(hasDataCheck, "长文本但无数值指标应生成数据来源核对检查项");
        }
    }

    // ════════════════ TOPIC_PLANNING 动态检查项（P2 bug 修复） ════════════════

    @Nested
    @DisplayName("TOPIC_PLANNING 动态检查项")
    class TopicPlanningDynamic {

        @Test
        @DisplayName("缺少 trendingKeywords 键应生成补充热点检查项（P2 bug 修复）")
        void missingTrendingKeywords_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            // 不包含 trendingKeywords 键
            outputs.put("topic", "技术分享");

            List<String> checklist = generator.generateChecklist(AgentStage.TOPIC_PLANNING, outputs);

            boolean hasHotKeywordsWarning = checklist.stream()
                    .anyMatch(s -> s.contains("热点关键词") && s.contains("动态"));
            assertTrue(hasHotKeywordsWarning, "缺少 trendingKeywords 应生成补充热点检查项");
        }

        @Test
        @DisplayName("trendingKeywords 键存在且值非空时不应生成热点警告")
        void existingTrendingKeywords_shouldNotWarn() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trendingKeywords", "AI, 大模型, 内容运营");

            List<String> checklist = generator.generateChecklist(AgentStage.TOPIC_PLANNING, outputs);

            boolean hasHotKeywordsWarning = checklist.stream()
                    .anyMatch(s -> s.contains("热点关键词") && s.contains("动态"));
            assertFalse(hasHotKeywordsWarning, "trendingKeywords 存在且非空时不应生成热点警告");
        }

        @Test
        @DisplayName("缺少 competitorAnalysis 应生成竞品分析检查项")
        void missingCompetitorAnalysis_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trendingKeywords", "AI");
            // 不包含 competitorAnalysis

            List<String> checklist = generator.generateChecklist(AgentStage.TOPIC_PLANNING, outputs);

            boolean hasCompetitorWarning = checklist.stream()
                    .anyMatch(s -> s.contains("竞品分析") && s.contains("动态"));
            assertTrue(hasCompetitorWarning, "缺少竞品分析应生成动态检查项");
        }
    }

    // ════════════════ CONTENT_CREATION 动态检查项 ════════════════

    @Nested
    @DisplayName("CONTENT_CREATION 动态检查项")
    class ContentCreationDynamic {

        @Test
        @DisplayName("wordCount 超过 3000 应生成拆分建议检查项")
        void longContent_shouldGenerateSplitWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("wordCount", 5000);

            List<String> checklist = generator.generateChecklist(AgentStage.CONTENT_CREATION, outputs);

            boolean hasSplitWarning = checklist.stream()
                    .anyMatch(s -> s.contains("篇幅较长") && s.contains("拆分"));
            assertTrue(hasSplitWarning, "长内容应生成拆分建议检查项");
        }

        @Test
        @DisplayName("wordCount 低于 300 应生成补充建议检查项")
        void shortContent_shouldGenerateExpandWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("wordCount", 200);

            List<String> checklist = generator.generateChecklist(AgentStage.CONTENT_CREATION, outputs);

            boolean hasExpandWarning = checklist.stream()
                    .anyMatch(s -> s.contains("篇幅较短") && s.contains("补充"));
            assertTrue(hasExpandWarning, "短内容应生成补充建议检查项");
        }

        @Test
        @DisplayName("内容缺少个人化标记应生成个人化检查项")
        void noPersonalElement_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("content", "这是一篇关于技术分享的文章，主要介绍了相关概念和方法。");

            List<String> checklist = generator.generateChecklist(AgentStage.CONTENT_CREATION, outputs);

            boolean hasPersonalWarning = checklist.stream()
                    .anyMatch(s -> s.contains("个人化") && s.contains("动态"));
            assertTrue(hasPersonalWarning, "缺少个人化标记应生成动态检查项");
        }
    }

    // ════════════════ PUBLISHING 动态检查项 ════════════════

    @Nested
    @DisplayName("PUBLISHING 动态检查项")
    class PublishingDynamic {

        @Test
        @DisplayName("存在 failedPlatforms 应生成平台失败检查项")
        void failedPlatforms_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("failedPlatforms", "微信公众号, 小红书");

            List<String> checklist = generator.generateChecklist(AgentStage.PUBLISHING, outputs);

            boolean hasFailedWarning = checklist.stream()
                    .anyMatch(s -> s.contains("发布失败") && s.contains("动态"));
            assertTrue(hasFailedWarning, "发布失败应生成动态检查项");
        }

        @Test
        @DisplayName("publishStatus 包含 fail 应生成状态异常检查项")
        void failedStatus_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("publishStatus", "partial_fail");

            List<String> checklist = generator.generateChecklist(AgentStage.PUBLISHING, outputs);

            boolean hasStatusWarning = checklist.stream()
                    .anyMatch(s -> s.contains("发布状态异常") && s.contains("动态"));
            assertTrue(hasStatusWarning, "发布状态异常应生成动态检查项");
        }
    }

    // ════════════════ OPTIMIZATION 动态检查项 ════════════════

    @Nested
    @DisplayName("OPTIMIZATION 动态检查项")
    class OptimizationDynamic {

        @Test
        @DisplayName("recommendations 为空应生成补充建议检查项")
        void emptyRecommendations_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("recommendations", List.of());

            List<String> checklist = generator.generateChecklist(AgentStage.OPTIMIZATION, outputs);

            boolean hasEmptyWarning = checklist.stream()
                    .anyMatch(s -> s.contains("优化建议") && s.contains("空") && s.contains("动态"));
            assertTrue(hasEmptyWarning, "空建议列表应生成动态检查项");
        }

        @Test
        @DisplayName("内容缺少回滚方案应生成回滚检查项")
        void noRollbackPlan_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("content", "建议优化标题，调整发布时间，增加互动引导。");

            List<String> checklist = generator.generateChecklist(AgentStage.OPTIMIZATION, outputs);

            boolean hasRollbackWarning = checklist.stream()
                    .anyMatch(s -> s.contains("回滚") && s.contains("动态"));
            assertTrue(hasRollbackWarning, "缺少回滚方案应生成动态检查项");
        }
    }

    // ════════════════ IMAGE_DESIGN 动态检查项 ════════════════

    @Nested
    @DisplayName("IMAGE_DESIGN 动态检查项")
    class ImageDesignDynamic {

        @Test
        @DisplayName("imageCount 低于 3 应生成配图不足检查项")
        void fewImages_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("imageCount", 2);

            List<String> checklist = generator.generateChecklist(AgentStage.IMAGE_DESIGN, outputs);

            boolean hasImageWarning = checklist.stream()
                    .anyMatch(s -> s.contains("配图数量较少") && s.contains("动态"));
            assertTrue(hasImageWarning, "配图不足应生成动态检查项");
        }

        @Test
        @DisplayName("缺少 platformAdapted 应生成平台适配检查项")
        void missingPlatformAdapted_shouldGenerateWarning() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("imageCount", 5);

            List<String> checklist = generator.generateChecklist(AgentStage.IMAGE_DESIGN, outputs);

            boolean hasAdaptWarning = checklist.stream()
                    .anyMatch(s -> s.contains("平台适配") && s.contains("动态"));
            assertTrue(hasAdaptWarning, "缺少平台适配信息应生成动态检查项");
        }
    }
}
