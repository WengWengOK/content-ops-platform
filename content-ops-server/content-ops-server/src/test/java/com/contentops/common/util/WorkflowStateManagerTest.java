package com.contentops.common.util;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkflowStateManager 测试（P0：数据库持久化 + 锁租约续期 + Redis 降级）。
 *
 * <p>使用真实 H2 内存库验证持久化与原子更新；Redis 仅以 mock 验证锁行为。
 */
@DisplayName("WorkflowStateManager 测试（数据库持久化 + 分布式锁）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowStateManagerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private WorkflowStateManager stateManager;

    private final Map<String, String> lockValues = new HashMap<>();

    @BeforeEach
    void setUp() {
        // 每个测试方法使用独立的内存库，避免同 JVM 内跨方法串数据
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wfsm-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contentops_workflow (
                    workflow_id  VARCHAR(64)      PRIMARY KEY,
                    context_json VARCHAR(1000000) NOT NULL,
                    owner_id     VARCHAR(64),
                    created_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    lockValues.put(invocation.getArgument(0), invocation.getArgument(1));
                    return true;
                });
        when(valueOps.get(anyString()))
                .thenAnswer(invocation -> lockValues.get(invocation.getArgument(0)));

        stateManager = new WorkflowStateManager(redisTemplate, jdbcTemplate);
    }

    private TaskContext sample(String workflowId, String status) {
        return TaskContext.builder()
                .workflowId(workflowId)
                .ownerId("user-a")
                .currentStage(AgentStage.TOPIC_PLANNING.getCode())
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .cycleCount(1)
                .build();
    }

    // ════════════════ 数据库持久化 ════════════════

    @Test
    @DisplayName("保存后可完整加载（数据库往返）")
    void saveAndLoad_roundTrip() {
        TaskContext ctx = sample("wf-001", TaskStatus.PENDING.name());
        ctx.setInputs(Map.of("topic", "AI 趋势"));

        stateManager.saveWorkflowState("wf-001", ctx);
        Optional<TaskContext> loaded = stateManager.loadWorkflowState("wf-001");

        assertTrue(loaded.isPresent());
        assertEquals("wf-001", loaded.get().getWorkflowId());
        assertEquals("user-a", loaded.get().getOwnerId());
        assertEquals("AI 趋势", loaded.get().getInputs().get("topic"));
    }

    @Test
    @DisplayName("重复保存应更新同一行而非插入新行")
    void saveTwice_shouldUpdate() {
        TaskContext ctx = sample("wf-002", TaskStatus.PENDING.name());
        stateManager.saveWorkflowState("wf-002", ctx);
        ctx.setStatus(TaskStatus.IN_PROGRESS.name());
        stateManager.saveWorkflowState("wf-002", ctx);

        Optional<TaskContext> loaded = stateManager.loadWorkflowState("wf-002");
        assertEquals(TaskStatus.IN_PROGRESS.name(), loaded.get().getStatus());
        assertEquals(1, stateManager.listAllWorkflows().size());
    }

    @Test
    @DisplayName("加载不存在的工作流返回 empty")
    void loadMissing_shouldReturnEmpty() {
        assertTrue(stateManager.loadWorkflowState("nonexistent").isEmpty());
    }

    @Test
    @DisplayName("listAllWorkflows 返回全部并按更新倒序")
    void listAll_shouldReturnAll() {
        stateManager.saveWorkflowState("wf-1", sample("wf-1", TaskStatus.COMPLETED.name()));
        stateManager.saveWorkflowState("wf-2", sample("wf-2", TaskStatus.PENDING.name()));

        List<TaskContext> all = stateManager.listAllWorkflows();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(c -> "wf-1".equals(c.getWorkflowId())));
        assertTrue(all.stream().anyMatch(c -> "wf-2".equals(c.getWorkflowId())));
    }

    @Test
    @DisplayName("listWorkflowsByOwner 只返回该用户的工作流（P0 数据隔离）")
    void listByOwner_shouldFilter() {
        TaskContext mine = sample("wf-owner-1", TaskStatus.PENDING.name());
        TaskContext other = sample("wf-owner-2", TaskStatus.PENDING.name());
        other.setOwnerId("user-b");
        stateManager.saveWorkflowState("wf-owner-1", mine);
        stateManager.saveWorkflowState("wf-owner-2", other);

        List<TaskContext> mineList = stateManager.listWorkflowsByOwner("user-a");
        assertEquals(1, mineList.size());
        assertEquals("wf-owner-1", mineList.get(0).getWorkflowId());
    }

    @Test
    @DisplayName("删除后加载为空")
    void delete_shouldRemove() {
        stateManager.saveWorkflowState("wf-del", sample("wf-del", TaskStatus.PENDING.name()));
        stateManager.deleteWorkflowState("wf-del");
        assertTrue(stateManager.loadWorkflowState("wf-del").isEmpty());
    }

    // ════════════════ 分布式锁 ════════════════

    @Test
    @DisplayName("executeWithLock 应获取 Redis 锁并释放")
    void executeWithLock_acquiresAndReleases() {
        AtomicInteger executed = new AtomicInteger();

        stateManager.executeWithLock("wf-lock-001", wfId -> executed.incrementAndGet());

        assertEquals(1, executed.get());
        verify(redisTemplate).delete("contentops:lock:workflow:wf-lock-001");
    }

    @Test
    @DisplayName("Redis 不可用时降级为进程内锁，业务仍执行")
    void executeWithLock_redisDown_usesLocalLock() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        AtomicInteger executed = new AtomicInteger();

        stateManager.executeWithLock("wf-lock-002", wfId -> executed.incrementAndGet());

        assertEquals(1, executed.get());
    }

    @Test
    @DisplayName("updateWorkflowStateAtomically 在锁保护下完成读改写")
    void updateAtomically_loadModifySave() {
        stateManager.saveWorkflowState("wf-atomic", sample("wf-atomic", TaskStatus.PENDING.name()));

        stateManager.updateWorkflowStateAtomically("wf-atomic",
                ctx -> ctx.setStatus(TaskStatus.AWAITING_HUMAN.name()));

        assertEquals(TaskStatus.AWAITING_HUMAN.name(),
                stateManager.loadWorkflowState("wf-atomic").get().getStatus());
        assertNotNull(stateManager.loadWorkflowState("wf-atomic").get().getUpdatedAt());
    }

    @Test
    @DisplayName("mergeArtifacts 应原子合并产物")
    void mergeArtifacts_merges() {
        TaskContext ctx = sample("wf-merge", TaskStatus.PENDING.name());
        ctx.setAccumulatedArtifacts(new HashMap<>(Map.of("key1", "val1")));
        stateManager.saveWorkflowState("wf-merge", ctx);

        stateManager.mergeArtifacts("wf-merge", Map.of("key2", "val2"));

        Map<String, Object> artifacts =
                stateManager.loadWorkflowState("wf-merge").get().getAccumulatedArtifacts();
        assertEquals("val1", artifacts.get("key1"));
        assertEquals("val2", artifacts.get("key2"));
    }
}
