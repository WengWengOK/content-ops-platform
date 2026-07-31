package com.contentops.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentStage} 枚举单元测试。
 *
 * <p>覆盖 6 个阶段的 next / previous 循环导航、fromCode 解析以及
 * getCode / getNameCn 元数据。
 */
@DisplayName("AgentStage 枚举测试")
class AgentStageTest {

    @Nested
    @DisplayName("next 下一阶段")
    class NextStage {

        @Test
        @DisplayName("next：应按流水线顺序返回下一阶段")
        void next_shouldReturnNextStage() {
            assertEquals(AgentStage.CONTENT_CREATION, AgentStage.TOPIC_PLANNING.next());
            assertEquals(AgentStage.IMAGE_DESIGN, AgentStage.CONTENT_CREATION.next());
            assertEquals(AgentStage.PUBLISHING, AgentStage.IMAGE_DESIGN.next());
            assertEquals(AgentStage.DATA_ANALYSIS, AgentStage.PUBLISHING.next());
            assertEquals(AgentStage.OPTIMIZATION, AgentStage.DATA_ANALYSIS.next());
        }

        @Test
        @DisplayName("next：OPTIMIZATION 应循环回到 TOPIC_PLANNING")
        void next_optimization_shouldLoopBack() {
            assertEquals(AgentStage.TOPIC_PLANNING, AgentStage.OPTIMIZATION.next());
        }
    }

    @Nested
    @DisplayName("previous 上一阶段")
    class PreviousStage {

        @Test
        @DisplayName("previous：应按流水线顺序返回上一阶段")
        void previous_shouldReturnPreviousStage() {
            assertEquals(AgentStage.TOPIC_PLANNING, AgentStage.CONTENT_CREATION.previous());
            assertEquals(AgentStage.CONTENT_CREATION, AgentStage.IMAGE_DESIGN.previous());
            assertEquals(AgentStage.IMAGE_DESIGN, AgentStage.PUBLISHING.previous());
            assertEquals(AgentStage.PUBLISHING, AgentStage.DATA_ANALYSIS.previous());
            assertEquals(AgentStage.DATA_ANALYSIS, AgentStage.OPTIMIZATION.previous());
        }

        @Test
        @DisplayName("previous：TOPIC_PLANNING 应循环回到 OPTIMIZATION")
        void previous_topicPlanning_shouldReturnOptimization() {
            assertEquals(AgentStage.OPTIMIZATION, AgentStage.TOPIC_PLANNING.previous());
        }
    }

    @Nested
    @DisplayName("fromCode 按编码查找")
    class FromCode {

        @Test
        @DisplayName("fromCode：应返回正确的阶段")
        void fromCode_shouldReturnCorrectStage() {
            assertEquals(AgentStage.TOPIC_PLANNING, AgentStage.fromCode("topic-planning"));
            assertEquals(AgentStage.CONTENT_CREATION, AgentStage.fromCode("content-creation"));
            assertEquals(AgentStage.IMAGE_DESIGN, AgentStage.fromCode("image-design"));
            assertEquals(AgentStage.PUBLISHING, AgentStage.fromCode("publishing"));
            assertEquals(AgentStage.DATA_ANALYSIS, AgentStage.fromCode("data-analysis"));
            assertEquals(AgentStage.OPTIMIZATION, AgentStage.fromCode("optimization"));
        }

        @Test
        @DisplayName("fromCode：未知编码应抛出 IllegalArgumentException")
        void fromCode_unknownCode_shouldThrow() {
            assertThrows(IllegalArgumentException.class, () -> AgentStage.fromCode("unknown"));
        }
    }

    @Nested
    @DisplayName("getCode / getNameCn 元数据")
    class Metadata {

        @Test
        @DisplayName("getCode：应返回正确的编码")
        void getCode_shouldReturnCorrectCode() {
            assertEquals("topic-planning", AgentStage.TOPIC_PLANNING.getCode());
            assertEquals("content-creation", AgentStage.CONTENT_CREATION.getCode());
            assertEquals("image-design", AgentStage.IMAGE_DESIGN.getCode());
            assertEquals("publishing", AgentStage.PUBLISHING.getCode());
            assertEquals("data-analysis", AgentStage.DATA_ANALYSIS.getCode());
            assertEquals("optimization", AgentStage.OPTIMIZATION.getCode());
        }

        @Test
        @DisplayName("getNameCn：应返回正确的中文名称")
        void getNameCn_shouldReturnChineseName() {
            assertEquals("选题策划", AgentStage.TOPIC_PLANNING.getNameCn());
            assertEquals("内容创作", AgentStage.CONTENT_CREATION.getNameCn());
            assertEquals("配图设计", AgentStage.IMAGE_DESIGN.getNameCn());
            assertEquals("排版发布", AgentStage.PUBLISHING.getNameCn());
            assertEquals("数据分析", AgentStage.DATA_ANALYSIS.getNameCn());
            assertEquals("优化迭代", AgentStage.OPTIMIZATION.getNameCn());
        }
    }
}
