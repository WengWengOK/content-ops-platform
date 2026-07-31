package com.contentops.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SubStage} 子阶段枚举单元测试。
 *
 * <p>覆盖 ofStage / hasSubStages / fullCode / next / firstOf / fromCode / fromFullCode，
 * 验证「先搭框架，再写初稿」的渐进式生成行为。
 */
@DisplayName("SubStage 子阶段枚举测试")
class SubStageTest {

    @Nested
    @DisplayName("ofStage 按父阶段查询")
    class OfStage {

        @Test
        @DisplayName("ofStage：CONTENT_CREATION 应返回 outline、draft 两个子阶段（按顺序）")
        void ofStage_contentCreation_shouldReturnOutlineAndDraft() {
            List<SubStage> subs = SubStage.ofStage(AgentStage.CONTENT_CREATION);

            assertEquals(2, subs.size());
            assertEquals(SubStage.CONTENT_OUTLINE, subs.get(0));
            assertEquals(SubStage.CONTENT_DRAFT, subs.get(1));
        }

        @Test
        @DisplayName("ofStage：TOPIC_PLANNING 没有子阶段，应返回空列表")
        void ofStage_topicPlanning_shouldReturnEmptyList() {
            List<SubStage> subs = SubStage.ofStage(AgentStage.TOPIC_PLANNING);

            assertTrue(subs.isEmpty());
        }
    }

    @Nested
    @DisplayName("hasSubStages 判断")
    class HasSubStages {

        @Test
        @DisplayName("hasSubStages：CONTENT_CREATION 应返回 true")
        void hasSubStages_contentCreation_shouldReturnTrue() {
            assertTrue(SubStage.hasSubStages(AgentStage.CONTENT_CREATION));
        }

        @Test
        @DisplayName("hasSubStages：TOPIC_PLANNING 应返回 false")
        void hasSubStages_topicPlanning_shouldReturnFalse() {
            assertFalse(SubStage.hasSubStages(AgentStage.TOPIC_PLANNING));
        }
    }

    @Nested
    @DisplayName("fullCode 完整标识")
    class FullCode {

        @Test
        @DisplayName("fullCode：应返回 {parentCode}:{subCode} 格式")
        void fullCode_shouldReturnParentColonSub() {
            assertEquals("content-creation:outline", SubStage.CONTENT_OUTLINE.fullCode());
            assertEquals("content-creation:draft", SubStage.CONTENT_DRAFT.fullCode());
            assertEquals("image-design:styles", SubStage.IMAGE_STYLES.fullCode());
            assertEquals("image-design:generate", SubStage.IMAGE_GENERATE.fullCode());
        }
    }

    @Nested
    @DisplayName("next 下一子阶段")
    class NextSubStage {

        @Test
        @DisplayName("next：CONTENT_OUTLINE 的下一个应为 CONTENT_DRAFT")
        void next_outline_shouldReturnDraft() {
            assertEquals(SubStage.CONTENT_DRAFT, SubStage.CONTENT_OUTLINE.next());
        }

        @Test
        @DisplayName("next：CONTENT_DRAFT 为最后一个子阶段，应返回 null")
        void next_draft_shouldReturnNull() {
            assertNull(SubStage.CONTENT_DRAFT.next());
        }
    }

    @Nested
    @DisplayName("firstOf 第一个子阶段")
    class FirstOf {

        @Test
        @DisplayName("firstOf：CONTENT_CREATION 应返回 CONTENT_OUTLINE")
        void firstOf_contentCreation_shouldReturnOutline() {
            assertEquals(SubStage.CONTENT_OUTLINE, SubStage.firstOf(AgentStage.CONTENT_CREATION));
        }

        @Test
        @DisplayName("firstOf：TOPIC_PLANNING 无子阶段，应返回 null")
        void firstOf_topicPlanning_shouldReturnNull() {
            assertNull(SubStage.firstOf(AgentStage.TOPIC_PLANNING));
        }
    }

    @Nested
    @DisplayName("fromCode / fromFullCode 查找")
    class FromCodeLookup {

        @Test
        @DisplayName("fromCode：outline 应返回 CONTENT_OUTLINE")
        void fromCode_shouldReturnCorrectSubStage() {
            assertEquals(SubStage.CONTENT_OUTLINE, SubStage.fromCode("outline"));
            assertEquals(SubStage.CONTENT_DRAFT, SubStage.fromCode("draft"));
            assertEquals(SubStage.IMAGE_STYLES, SubStage.fromCode("styles"));
            assertEquals(SubStage.IMAGE_GENERATE, SubStage.fromCode("generate"));
        }

        @Test
        @DisplayName("fromFullCode：content-creation:outline 应返回 CONTENT_OUTLINE")
        void fromFullCode_shouldReturnCorrectSubStage() {
            assertEquals(SubStage.CONTENT_OUTLINE, SubStage.fromFullCode("content-creation:outline"));
            assertEquals(SubStage.CONTENT_DRAFT, SubStage.fromFullCode("content-creation:draft"));
            assertEquals(SubStage.IMAGE_GENERATE, SubStage.fromFullCode("image-design:generate"));
        }
    }
}
