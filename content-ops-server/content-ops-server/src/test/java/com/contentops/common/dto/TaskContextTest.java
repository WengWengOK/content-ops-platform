package com.contentops.common.dto;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskContext 循环控制方法单元测试（A计划循环优化）。
 *
 * <p>验证：
 * <ul>
 *   <li>{@code hasRemainingCycles} 在不同 cycleCount/maxCycles 下的判定</li>
 *   <li>默认 maxCycles 为 3</li>
 *   <li>{@code startNewCycle} 的副作用：递增 cycleCount、重置阶段、重置状态、注入优化反馈与轮次编号</li>
 *   <li>{@code snapshotCurrentCycle} 将当前轮次产物存入 cycleHistory</li>
 *   <li>{@code startNewCycle} 先快照（使用递增前的 cycleCount）再递增的执行顺序</li>
 * </ul>
 *
 * <p><b>关于默认 maxCycles：</b> {@code maxCycles} 字段初始化器为 {@code = 3}，
 * 但 Lombok {@code @Builder}（未加 {@code @Builder.Default}）不会应用字段初始化器，
 * 因此通过 builder 构建的实例 maxCycles 为 0。验证默认值时使用无参构造 {@code new TaskContext()}，
 * 其余用例均在 builder 中显式设置 maxCycles。
 */
@DisplayName("TaskContext 循环控制测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaskContextTest {

    // ════════════════ hasRemainingCycles ════════════════

    @Test
    @Order(1)
    @DisplayName("cycleCount 小于 maxCycles 时 hasRemainingCycles 应返回 true")
    void hasRemainingCycles_whenBelowMax_shouldReturnTrue() {
        TaskContext context = TaskContext.builder()
                .cycleCount(1)
                .maxCycles(3)
                .build();

        assertTrue(context.hasRemainingCycles(), "1 < 3 应有剩余轮次");
    }

    @Test
    @Order(2)
    @DisplayName("cycleCount 等于 maxCycles 时 hasRemainingCycles 应返回 false")
    void hasRemainingCycles_whenAtMax_shouldReturnFalse() {
        TaskContext context = TaskContext.builder()
                .cycleCount(3)
                .maxCycles(3)
                .build();

        assertFalse(context.hasRemainingCycles(), "3 < 3 为 false，无剩余轮次");
    }

    @Test
    @Order(3)
    @DisplayName("默认 maxCycles 应为 3")
    void hasRemainingCycles_defaultMaxCycles_shouldBe3() {
        // 使用无参构造以观察字段默认值（builder 不会应用字段初始化器）
        TaskContext context = new TaskContext();

        assertEquals(3, context.getMaxCycles(), "默认最大循环次数应为 3");
        assertTrue(context.hasRemainingCycles(), "默认 cycleCount=0 < maxCycles=3 应有剩余轮次");
    }

    // ════════════════ startNewCycle ════════════════

    @Test
    @Order(4)
    @DisplayName("startNewCycle 应将 cycleCount 递增 1")
    void startNewCycle_shouldIncrementCycleCount() {
        TaskContext context = TaskContext.builder()
                .cycleCount(1)
                .maxCycles(3)
                .build();

        context.startNewCycle();

        assertEquals(2, context.getCycleCount(), "cycleCount 应从 1 递增为 2");
    }

    @Test
    @Order(5)
    @DisplayName("startNewCycle 应将 currentStage 重置为 topic-planning")
    void startNewCycle_shouldResetStageToTopicPlanning() {
        TaskContext context = TaskContext.builder()
                .currentStage(AgentStage.OPTIMIZATION.getCode())
                .cycleCount(1)
                .maxCycles(3)
                .build();

        context.startNewCycle();

        assertEquals(AgentStage.TOPIC_PLANNING.getCode(), context.getCurrentStage(),
                "新轮次应从 TOPIC_PLANNING 开始");
    }

    @Test
    @Order(6)
    @DisplayName("startNewCycle 应将 status 设置为 PENDING")
    void startNewCycle_shouldSetStatusPending() {
        TaskContext context = TaskContext.builder()
                .status(TaskStatus.IN_PROGRESS.name())
                .cycleCount(1)
                .maxCycles(3)
                .build();

        context.startNewCycle();

        assertEquals(TaskStatus.PENDING.name(), context.getStatus(),
                "新轮次状态应重置为 PENDING");
    }

    @Test
    @Order(7)
    @DisplayName("startNewCycle 应将 lastOptimizationFeedback 注入到 inputs.optimizationFeedback")
    void startNewCycle_shouldInjectOptimizationFeedback() {
        TaskContext context = TaskContext.builder()
                .cycleCount(1)
                .maxCycles(3)
                .lastOptimizationFeedback("上一轮优化反馈：增强数据图表")
                .inputs(new HashMap<>())
                .build();

        context.startNewCycle();

        assertNotNull(context.getInputs(), "inputs 不应为 null");
        assertEquals("上一轮优化反馈：增强数据图表",
                context.getInputs().get("optimizationFeedback"),
                "应将优化反馈注入 inputs.optimizationFeedback");
    }

    @Test
    @Order(8)
    @DisplayName("startNewCycle 应将新轮次编号注入到 inputs.cycleNumber")
    void startNewCycle_shouldInjectCycleNumber() {
        TaskContext context = TaskContext.builder()
                .cycleCount(1)
                .maxCycles(3)
                .lastOptimizationFeedback("反馈")
                .inputs(new HashMap<>())
                .build();

        context.startNewCycle();

        assertNotNull(context.getInputs(), "inputs 不应为 null");
        assertEquals(Integer.valueOf(2), context.getInputs().get("cycleNumber"),
                "cycleNumber 应为递增后的 cycleCount=2");
    }

    // ════════════════ snapshotCurrentCycle ════════════════

    @Test
    @Order(9)
    @DisplayName("snapshotCurrentCycle 应将当前轮次产物存入 cycleHistory 的 cycle-{N} 键")
    void snapshotCurrentCycle_shouldStoreInCycleHistory() {
        Map<String, Object> artifacts = new HashMap<>();
        artifacts.put("topic", "选题A");
        artifacts.put(AgentStage.OPTIMIZATION.getCode(), "优化建议");

        TaskContext context = TaskContext.builder()
                .cycleCount(1)
                .maxCycles(3)
                .accumulatedArtifacts(artifacts)
                .build();

        context.snapshotCurrentCycle();

        assertNotNull(context.getCycleHistory(), "cycleHistory 不应为 null");
        assertTrue(context.getCycleHistory().containsKey("cycle-1"),
                "应存入 cycle-1 键");
        assertNotNull(context.getCycleHistory().get("cycle-1"),
                "cycle-1 快照内容不应为 null");
    }

    @Test
    @Order(10)
    @DisplayName("startNewCycle 应先快照（使用递增前的 cycleCount）再递增")
    void startNewCycle_shouldCallSnapshotFirst() {
        Map<String, Object> artifacts = new HashMap<>();
        artifacts.put("topic", "选题A");

        TaskContext context = TaskContext.builder()
                .cycleCount(1)
                .maxCycles(3)
                .accumulatedArtifacts(artifacts)
                .build();

        context.startNewCycle();

        // 递增后 cycleCount=2，但快照应使用递增前的值 1 → 键为 cycle-1
        assertEquals(2, context.getCycleCount(), "cycleCount 应递增为 2");
        assertNotNull(context.getCycleHistory(), "cycleHistory 不应为 null");
        assertTrue(context.getCycleHistory().containsKey("cycle-1"),
                "快照应使用递增前的 cycleCount=1 作为键");
        assertFalse(context.getCycleHistory().containsKey("cycle-2"),
                "快照不应使用递增后的 cycleCount=2 作为键");
    }
}
