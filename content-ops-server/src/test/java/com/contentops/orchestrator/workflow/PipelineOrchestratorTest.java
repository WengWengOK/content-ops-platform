package com.contentops.orchestrator.workflow;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PipelineOrchestrator 门面类单元测试。
 *
 * <p>PipelineOrchestrator 在 P2-13 重构后成为薄门面，仅将调用委托给四个单一职责的协作者：
 * <ul>
 *   <li>{@link StageExecutor} — 单阶段执行逻辑</li>
 *   <li>{@link SubStageExecutor} — 子阶段（渐进式生成）逻辑</li>
 *   <li>{@link CycleHandler} — 循环边界控制（OPTIMIZATION → TOPIC_PLANNING）</li>
 *   <li>{@link QualityEnricher} — 质量评估 + 人工行动清单</li>
 * </ul>
 *
 * <p>本测试通过 Mockito 模拟四个依赖，验证：
 * <ul>
 *   <li>{@code executeStage} 正确委托给 {@link StageExecutor#executeStage(TaskContext)}</li>
 *   <li>{@code confirmAndProceedSubStage} 正确委托给
 *       {@link SubStageExecutor#confirmAndProceedSubStage(TaskContext, Map)}</li>
 *   <li>{@code checkAndHandleCycleBoundary} 正确委托给
 *       {@link CycleHandler#checkAndHandleCycleBoundary(TaskContext, AgentStage, AgentStage)}
 *       并原样返回其布尔结果</li>
 * </ul>
 */
@DisplayName("PipelineOrchestrator 门面委托测试")
@ExtendWith(MockitoExtension.class)
class PipelineOrchestratorTest {

    @Mock
    private StageExecutor stageExecutor;

    @Mock
    private SubStageExecutor subStageExecutor;

    @Mock
    private CycleHandler cycleHandler;

    @Mock
    private QualityEnricher qualityEnricher;

    @InjectMocks
    private PipelineOrchestrator pipelineOrchestrator;

    // ════════════════ executeStage ════════════════

    @Test
    @DisplayName("executeStage 应将上下文委托给 StageExecutor 执行")
    void executeStage_shouldDelegateToStageExecutor() {
        // given
        TaskContext context = TaskContext.builder()
                .workflowId("wf-delegate-001")
                .currentStage(AgentStage.TOPIC_PLANNING.getCode())
                .status(TaskStatus.PENDING.name())
                .build();

        // when
        pipelineOrchestrator.executeStage(context);

        // then —— 验证委托调用使用了同一个上下文对象
        verify(stageExecutor).executeStage(context);
    }

    @Test
    @DisplayName("executeStage 传入 null 上下文时仍应原样委托给 StageExecutor")
    void executeStage_shouldPassNullContext() {
        // when —— null 上下文也应被无差别地传递下去（门面不做校验）
        pipelineOrchestrator.executeStage(null);

        // then —— 验证委托调用收到了 null
        verify(stageExecutor).executeStage(null);
    }

    // ════════════════ confirmAndProceedSubStage ════════════════

    @Test
    @DisplayName("confirmAndProceedSubStage 应将上下文与反馈委托给 SubStageExecutor")
    void confirmAndProceedSubStage_shouldDelegateToSubStageExecutor() {
        // given
        TaskContext context = TaskContext.builder()
                .workflowId("wf-sub-001")
                .currentStage(AgentStage.CONTENT_CREATION.getCode())
                .currentSubStage("outline")
                .status(TaskStatus.AWAITING_HUMAN.name())
                .build();
        Map<String, Object> feedback = new HashMap<>();
        feedback.put("confirmedOutline", "一、引言\n二、正文\n三、结语");
        feedback.put("topic", "AI 内容运营实战");

        // when
        pipelineOrchestrator.confirmAndProceedSubStage(context, feedback);

        // then —— 验证委托调用使用了同一上下文与同一反馈 Map
        verify(subStageExecutor).confirmAndProceedSubStage(context, feedback);
    }

    @Test
    @DisplayName("confirmAndProceedSubStage 传入空反馈 Map 时仍应原样委托给 SubStageExecutor")
    void confirmAndProceedSubStage_withEmptyFeedback() {
        // given
        TaskContext context = TaskContext.builder()
                .workflowId("wf-sub-002")
                .currentStage(AgentStage.IMAGE_DESIGN.getCode())
                .currentSubStage("styles")
                .status(TaskStatus.AWAITING_HUMAN.name())
                .build();
        Map<String, Object> emptyFeedback = new HashMap<>();

        // when
        pipelineOrchestrator.confirmAndProceedSubStage(context, emptyFeedback);

        // then —— 空反馈也应无差别委托，由 SubStageExecutor 决定如何处理
        verify(subStageExecutor).confirmAndProceedSubStage(context, emptyFeedback);
    }

    // ════════════════ checkAndHandleCycleBoundary ════════════════

    @Test
    @DisplayName("checkAndHandleCycleBoundary 在 CycleHandler 返回 true 时应返回 true")
    void checkAndHandleCycleBoundary_shouldDelegateToCycleHandler() {
        // given —— OPTIMIZATION → TOPIC_PLANNING 为循环边界
        TaskContext context = TaskContext.builder()
                .workflowId("wf-cycle-001")
                .currentStage(AgentStage.OPTIMIZATION.getCode())
                .status(TaskStatus.AWAITING_HUMAN.name())
                .cycleCount(1)
                .maxCycles(3)
                .build();
        AgentStage currentStage = AgentStage.OPTIMIZATION;
        AgentStage nextStage = AgentStage.TOPIC_PLANNING;
        when(cycleHandler.checkAndHandleCycleBoundary(context, currentStage, nextStage))
                .thenReturn(true);

        // when
        boolean result = pipelineOrchestrator.checkAndHandleCycleBoundary(context, currentStage, nextStage);

        // then —— 验证委托调用并确认返回值被原样透传
        verify(cycleHandler).checkAndHandleCycleBoundary(context, currentStage, nextStage);
        assertTrue(result, "CycleHandler 返回 true 时门面应返回 true（已处理循环边界）");
    }

    @Test
    @DisplayName("checkAndHandleCycleBoundary 在非循环边界时应返回 false")
    void checkAndHandleCycleBoundary_whenNotCycleBoundary() {
        // given —— CONTENT_CREATION → IMAGE_DESIGN 非循环边界
        TaskContext context = TaskContext.builder()
                .workflowId("wf-cycle-002")
                .currentStage(AgentStage.CONTENT_CREATION.getCode())
                .status(TaskStatus.AWAITING_HUMAN.name())
                .cycleCount(1)
                .maxCycles(3)
                .build();
        AgentStage currentStage = AgentStage.CONTENT_CREATION;
        AgentStage nextStage = AgentStage.IMAGE_DESIGN;
        when(cycleHandler.checkAndHandleCycleBoundary(context, currentStage, nextStage))
                .thenReturn(false);

        // when
        boolean result = pipelineOrchestrator.checkAndHandleCycleBoundary(context, currentStage, nextStage);

        // then —— 验证委托调用并确认返回值被原样透传
        verify(cycleHandler).checkAndHandleCycleBoundary(context, currentStage, nextStage);
        assertFalse(result, "非循环边界时门面应返回 false（调用方可继续正常推进）");
    }
}
