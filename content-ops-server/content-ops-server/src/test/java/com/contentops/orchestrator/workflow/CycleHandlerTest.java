package com.contentops.orchestrator.workflow;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.util.WorkflowStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CycleHandler 单元测试 — 验证 OPTIMIZATION → TOPIC_PLANNING 循环边界处理逻辑。
 *
 * <p>覆盖：
 * <ul>
 *   <li>非循环边界直接返回 false 且不触发任何处理</li>
 *   <li>循环边界（OPTIMIZATION → TOPIC_PLANNING）识别并返回 true</li>
 *   <li>从 accumulatedArtifacts 提取 optimizationSuggestions 作为优化反馈</li>
 *   <li>有剩余轮次：调用 startNewCycle 并自动执行下一阶段</li>
 *   <li>无剩余轮次：将状态标记为 COMPLETED</li>
 *   <li>有剩余轮次：持久化工作流状态</li>
 * </ul>
 *
 * <p>依赖 {@link WorkflowStateManager} 与 {@link StageExecutor} 以 {@code @Mock} 注入；
 * {@link TaskContext} 使用真实实例（builder 构建，必要时以 {@code spy()} 包装以验证方法调用）。
 * 采用 lenient 严格度，避免在仅做 verify 的测试中触发严格存根校验。
 */
@DisplayName("CycleHandler 循环边界处理测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CycleHandlerTest {

    @Mock
    private WorkflowStateManager stateManager;

    @Mock
    private StageExecutor stageExecutor;

    private CycleHandler cycleHandler;

    @BeforeEach
    void setUp() {
        cycleHandler = new CycleHandler(stateManager, stageExecutor);
    }

    // ════════════════ 边界识别 ════════════════

    @Test
    @DisplayName("非循环边界（PUBLISHING→DATA_ANALYSIS）应返回 false 且不触发任何处理")
    void checkAndHandleCycleBoundary_nonCycleBoundary_shouldReturnFalse() {
        TaskContext context = TaskContext.builder()
                .workflowId("wf-001")
                .cycleCount(1)
                .maxCycles(3)
                .build();

        boolean result = cycleHandler.checkAndHandleCycleBoundary(
                context, AgentStage.PUBLISHING, AgentStage.DATA_ANALYSIS);

        assertFalse(result, "非循环边界应返回 false");
        verifyNoInteractions(stateManager);
        verifyNoInteractions(stageExecutor);
    }

    @Test
    @DisplayName("循环边界（OPTIMIZATION→TOPIC_PLANNING）应返回 true 表示已处理")
    void checkAndHandleCycleBoundary_optimizationToTopicPlanning_shouldReturnTrue() {
        TaskContext context = TaskContext.builder()
                .workflowId("wf-002")
                .cycleCount(1)
                .maxCycles(3)
                .build();

        boolean result = cycleHandler.checkAndHandleCycleBoundary(
                context, AgentStage.OPTIMIZATION, AgentStage.TOPIC_PLANNING);

        assertTrue(result, "循环边界应返回 true");
    }

    @Test
    @DisplayName("循环边界应从 accumulatedArtifacts 提取 optimizationSuggestions 作为优化反馈")
    void checkAndHandleCycleBoundary_shouldExtractOptimizationFeedback() {
        Map<String, Object> optimizationData = new HashMap<>();
        optimizationData.put("optimizationSuggestions", "建议增加数据图表与对比维度");

        Map<String, Object> accumulatedArtifacts = new HashMap<>();
        accumulatedArtifacts.put(AgentStage.OPTIMIZATION.getCode(), optimizationData);

        TaskContext context = TaskContext.builder()
                .workflowId("wf-003")
                .cycleCount(1)
                .maxCycles(3)
                .accumulatedArtifacts(accumulatedArtifacts)
                .build();

        cycleHandler.checkAndHandleCycleBoundary(
                context, AgentStage.OPTIMIZATION, AgentStage.TOPIC_PLANNING);

        assertEquals("建议增加数据图表与对比维度", context.getLastOptimizationFeedback(),
                "应将 optimizationSuggestions 提取并存入 lastOptimizationFeedback");
    }

    // ════════════════ handleCycleBoundary ════════════════

    @Test
    @DisplayName("有剩余轮次时应调用 startNewCycle 并自动执行下一阶段")
    void handleCycleBoundary_withRemainingCycles_shouldStartNewCycle() {
        TaskContext context = spy(TaskContext.builder()
                .workflowId("wf-004")
                .cycleCount(1)
                .maxCycles(3)
                .build());

        cycleHandler.handleCycleBoundary(context, AgentStage.OPTIMIZATION, new HashMap<>());

        verify(context).startNewCycle();
        verify(stageExecutor).executeStage(context);
        assertEquals(2, context.getCycleCount(), "startNewCycle 后 cycleCount 应递增为 2");
    }

    @Test
    @DisplayName("无剩余轮次时应将状态标记为 COMPLETED")
    void handleCycleBoundary_noRemainingCycles_shouldMarkCompleted() {
        TaskContext context = spy(TaskContext.builder()
                .workflowId("wf-005")
                .cycleCount(3)
                .maxCycles(3)
                .build());

        cycleHandler.handleCycleBoundary(context, AgentStage.OPTIMIZATION, new HashMap<>());

        assertEquals(TaskStatus.COMPLETED.name(), context.getStatus(),
                "达到最大循环次数后状态应为 COMPLETED");
        verify(stateManager).saveWorkflowState(anyString(), any(TaskContext.class));
    }

    @Test
    @DisplayName("有剩余轮次时应持久化工作流状态")
    void handleCycleBoundary_withRemainingCycles_shouldSaveState() {
        TaskContext context = spy(TaskContext.builder()
                .workflowId("wf-006")
                .cycleCount(1)
                .maxCycles(3)
                .build());

        cycleHandler.handleCycleBoundary(context, AgentStage.OPTIMIZATION, new HashMap<>());

        verify(stateManager).saveWorkflowState(eq("wf-006"), any(TaskContext.class));
    }
}
