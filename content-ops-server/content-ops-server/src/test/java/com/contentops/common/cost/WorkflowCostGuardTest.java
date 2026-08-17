package com.contentops.common.cost;

import com.contentops.common.cost.CostGuardBlockedException.Reason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成本护栏单元测试：工作流 token 预算、成本预算、多平台分支归集、熔断器。
 */
class WorkflowCostGuardTest {

    private CostBudgetProperties properties() {
        CostBudgetProperties p = new CostBudgetProperties();
        p.setEnabled(true);
        p.setWorkflowTokenBudget(100);
        p.setWorkflowCostBudgetUsd(0); // 先关闭成本预算，单独测 token 预算
        p.setCircuitOpenFailures(3);
        p.setCircuitOpenSeconds(60);
        p.setFatalCircuitOpenSeconds(600);
        return p;
    }

    @Test
    @DisplayName("token 用量超过预算后应阻断后续调用")
    void budgetBlocksAfterUsageExceeded() {
        WorkflowCostGuard guard = new WorkflowCostGuard(properties());
        guard.recordUsage("wf-001", new TokenUsage(60, 0)); // 60 tokens
        guard.checkBlocked("wf-001"); // 未超预算，放行
        guard.recordUsage("wf-001", new TokenUsage(40, 0)); // 累计 100，达到预算上限

        CostGuardBlockedException ex = assertThrows(CostGuardBlockedException.class,
                () -> guard.checkBlocked("wf-001"));
        assertEquals(Reason.BUDGET_EXCEEDED, ex.getReason());
        assertTrue(ex.getMessage().contains(CostGuardBlockedException.BUDGET_MARKER));
    }

    @Test
    @DisplayName("多平台分支用量应归集到父工作流预算")
    void branchUsageChargesToParentWorkflow() {
        WorkflowCostGuard guard = new WorkflowCostGuard(properties());
        guard.recordUsage("wf-002:xiaohongshu", new TokenUsage(40, 0));
        guard.recordUsage("wf-002:wechat", new TokenUsage(40, 0));
        assertEquals(80, guard.workflowTokens("wf-002"));

        guard.recordUsage("wf-002:douyin", new TokenUsage(40, 0)); // 120
        assertThrows(CostGuardBlockedException.class,
                () -> guard.checkBlocked("wf-002:douyin"));
    }

    @Test
    @DisplayName("402 余额不足应立即打开熔断并阻断调用")
    void fatalErrorOpensCircuitImmediately() {
        WorkflowCostGuard guard = new WorkflowCostGuard(properties());
        guard.recordFailure(new RuntimeException("402 Payment Required: Insufficient Balance"));
        assertTrue(guard.isCircuitOpen());

        CostGuardBlockedException ex = assertThrows(CostGuardBlockedException.class,
                () -> guard.checkBlocked("wf-003"));
        assertEquals(Reason.CIRCUIT_OPEN, ex.getReason());
    }

    @Test
    @DisplayName("连续普通失败达阈值后打开熔断，reset 可恢复")
    void consecutiveFailuresOpenCircuitAndResetRecovers() {
        CostBudgetProperties p = properties();
        p.setCircuitOpenFailures(2);
        WorkflowCostGuard guard = new WorkflowCostGuard(p);

        guard.recordFailure(new RuntimeException("503 Service Unavailable"));
        assertFalse(guard.isCircuitOpen(), "第一次失败不应熔断");
        guard.recordFailure(new RuntimeException("500 Internal Server Error"));
        assertTrue(guard.isCircuitOpen(), "连续两次失败应熔断");
        assertThrows(CostGuardBlockedException.class, () -> guard.checkBlocked("wf-004"));

        guard.reset();
        assertFalse(guard.isCircuitOpen(), "reset 后应恢复");
        guard.checkBlocked("wf-004"); // 不再抛出
    }

    @Test
    @DisplayName("禁用成本护栏时预算超限也不阻断")
    void disabledGuardPasses() {
        CostBudgetProperties p = properties();
        p.setEnabled(false);
        WorkflowCostGuard guard = new WorkflowCostGuard(p);
        guard.recordUsage("wf-005", new TokenUsage(1000, 1000));
        guard.checkBlocked("wf-005"); // 不抛异常
    }
}
