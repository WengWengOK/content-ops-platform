package com.contentops.common.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多 Agent 协作框架纯逻辑单元测试。
 *
 * <p>验证 {@link AgentRole}、{@link AgentTask}（DAG）、{@link AgentResult}（merge）
 * 与 {@link AgentCommunicationProtocol} 的核心行为，不依赖 Spring 上下文与 LLM。
 */
class MultiAgentFrameworkTest {

    @Test
    void predefinedRoles_shouldBeResolvableByName() {
        assertEquals("supervisor", AgentRole.SUPERVISOR.roleName());
        assertEquals("writer", AgentRole.fromName("WRITER").roleName());
        assertEquals("critic", AgentRole.fromName("critic").roleName());
        assertEquals(5, AgentRole.PREDEFINED.size());
        assertTrue(AgentRole.WRITER.isCreative());
        assertFalse(AgentRole.REVIEWER.isCreative());
        assertThrows(IllegalArgumentException.class, () -> AgentRole.fromName("unknown"));
    }

    @Test
    void agentTask_topologicalSort_shouldRespectDependencies() {
        AgentTask a = AgentTask.simple("a", "调研", "researcher");
        AgentTask b = AgentTask.of("b", "写作", "writer", Map.of(), 5, List.of("a"));
        AgentTask c = AgentTask.of("c", "审稿", "reviewer", Map.of(), 5, List.of("b"));
        // 传入乱序
        List<AgentTask> sorted = AgentTask.topologicalSort(List.of(c, a, b));

        assertEquals(List.of("a", "b", "c"), sorted.stream().map(AgentTask::taskId).toList());
    }

    @Test
    void agentTask_shouldDetectCycle() {
        AgentTask a = AgentTask.of("a", "A", "writer", Map.of(), 1, List.of("b"));
        AgentTask b = AgentTask.of("b", "B", "writer", Map.of(), 1, List.of("a"));
        assertTrue(AgentTask.detectCycle(List.of(a, b)));
        assertThrows(IllegalStateException.class, () -> AgentTask.topologicalSort(List.of(a, b)));
    }

    @Test
    void agentTask_readyTasks_shouldRespectCompletedSet() {
        AgentTask a = AgentTask.simple("a", "A", "researcher");
        AgentTask b = AgentTask.of("b", "B", "writer", Map.of(), 5, List.of("a"));
        List<AgentTask> ready = AgentTask.readyTasks(List.of(a, b), Set.of());
        assertEquals(List.of("a"), ready.stream().map(AgentTask::taskId).toList());
        List<AgentTask> ready2 = AgentTask.readyTasks(List.of(a, b), Set.of("a"));
        assertEquals(List.of("b"), ready2.stream().map(AgentTask::taskId).toList());
    }

    @Test
    void agentResult_merge_shouldAggregateFields() {
        var usage1 = new dev.langchain4j.model.output.TokenUsage(10, 20, 30);
        var usage2 = new dev.langchain4j.model.output.TokenUsage(5, 5, 10);
        AgentResult r1 = AgentResult.success("t1", "调研结果", 1000L, usage1, 80);
        AgentResult r2 = AgentResult.success("t1", "写作结果", 3000L, usage2, 90);

        AgentResult merged = r1.merge(r2);

        assertTrue(merged.success());
        assertTrue(merged.output().contains("调研结果"));
        assertTrue(merged.output().contains("写作结果"));
        assertEquals(3000L, merged.executionTime());
        assertEquals(90, merged.qualityScore());
        assertEquals(15, merged.inputTokens());
        assertEquals(25, merged.outputTokens());
    }

    @Test
    void agentResult_mergeAll_shouldPropagateFailure() {
        AgentResult ok = AgentResult.success("t1", "ok", 1L, new dev.langchain4j.model.output.TokenUsage(1, 1, 2));
        AgentResult fail = AgentResult.failure("t2", "boom");
        AgentResult merged = AgentResult.mergeAll("root", List.of(ok, fail));
        assertFalse(merged.success());
        assertEquals(1, merged.errors().size());
    }

    @Test
    void communicationProtocol_shouldSupportP2PAndBroadcast() throws InterruptedException {
        AgentCommunicationProtocol protocol = new AgentCommunicationProtocol(64);
        protocol.registerAll(List.of("supervisor", "writer", "reviewer"));

        // 点对点
        assertTrue(protocol.send(AgentCommunicationProtocol.Message.taskAssignment("supervisor", "writer", "写文章")));
        AgentCommunicationProtocol.Message msg = protocol.receive("writer", 500, TimeUnit.MILLISECONDS);
        assertNotNull(msg);
        assertEquals(AgentCommunicationProtocol.MessageType.TASK_ASSIGNMENT, msg.type());
        assertEquals("写文章", msg.content());

        // 广播
        protocol.broadcast(AgentCommunicationProtocol.Message.feedback("supervisor", "*", "注意质量"));
        assertNotNull(protocol.receive("writer", 500, TimeUnit.MILLISECONDS));
        assertNotNull(protocol.receive("reviewer", 500, TimeUnit.MILLISECONDS));

        // 未注册接收方
        assertFalse(protocol.send(AgentCommunicationProtocol.Message.taskAssignment("supervisor", "ghost", "x")));
    }

    @Test
    void communicationProtocol_drain_shouldRemoveMessages() {
        AgentCommunicationProtocol protocol = new AgentCommunicationProtocol(64);
        protocol.register("worker");
        protocol.send(AgentCommunicationProtocol.Message.resultDelivery("worker", "worker", "r1"));
        protocol.send(AgentCommunicationProtocol.Message.resultDelivery("worker", "worker", "r2"));

        List<AgentCommunicationProtocol.Message> drained = protocol.drain("worker");
        assertEquals(2, drained.size());
        assertEquals(0, protocol.pending("worker"));
    }
}
