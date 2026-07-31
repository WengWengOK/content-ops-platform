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
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WorkflowStateManager 单元测试。
 *
 * <p>验证 P0-4 / P1-8 修复：
 * <ul>
 *   <li>SCAN 替代 KEYS 命令</li>
 *   <li>分布式锁保护状态读写</li>
 *   <li>原子更新操作（updateWorkflowStateAtomically）</li>
 *   <li>基本的状态保存/加载/删除</li>
 * </ul>
 */
@DisplayName("WorkflowStateManager 测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowStateManagerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private WorkflowStateManager stateManager;

    private TaskContext sampleContext;

    /** 捕获 setIfAbsent 传入的 lock value，用于后续 get 返回匹配值 */
    private final Map<String, String> lockValueStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        stateManager = new WorkflowStateManager(redisTemplate);
        lockValueStore.clear();

        sampleContext = TaskContext.builder()
                .workflowId("wf-test-001")
                .currentStage(AgentStage.TOPIC_PLANNING.getCode())
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .cycleCount(1)
                .build();
    }

    /**
     * 辅助方法：模拟 Redis SETNX + GET，使 releaseLock 能匹配到正确的 lockValue。
     */
    @SuppressWarnings("unchecked")
    private void setupLockMock(String lockKey) {
        when(valueOps.setIfAbsent(eq(lockKey), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String lockValue = invocation.getArgument(1);
                    lockValueStore.put(lockKey, lockValue);
                    return true;
                });
        when(valueOps.get(lockKey)).thenAnswer(invocation ->
                lockValueStore.get(invocation.getArgument(0)));
    }

    // ════════════════ 状态保存/加载测试 ════════════════

    @Test
    @DisplayName("保存工作流状态应写入 Redis 并设置 24h 过期")
    void saveWorkflowState_shouldSetWithTTL() {
        stateManager.saveWorkflowState("wf-test-001", sampleContext);

        verify(valueOps).set(
                eq("contentops:workflow:wf-test-001"),
                anyString(),
                eq(Duration.ofHours(24))
        );
    }

    @Test
    @DisplayName("加载存在的工作流状态应返回 Optional 包含数据")
    void loadWorkflowState_existing_shouldReturnData() {
        String json = "{\"workflowId\":\"wf-001\",\"status\":\"PENDING\"}";
        when(valueOps.get("contentops:workflow:wf-001")).thenReturn(json);

        Optional<TaskContext> result = stateManager.loadWorkflowState("wf-001");

        assertTrue(result.isPresent());
    }

    @Test
    @DisplayName("加载不存在的工作流状态应返回 empty")
    void loadWorkflowState_notFound_shouldReturnEmpty() {
        when(valueOps.get(anyString())).thenReturn(null);

        Optional<TaskContext> result = stateManager.loadWorkflowState("nonexistent");

        assertTrue(result.isEmpty());
    }

    // ════════════════ SCAN 命令测试（P1-8） ════════════════

    @Test
    @DisplayName("listAllWorkflows 应使用 SCAN 而非 KEYS")
    void listAllWorkflows_shouldUseScanNotKeys() {
        Cursor<String> mockCursor = mock(Cursor.class);
        when(mockCursor.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(mockCursor);

        List<TaskContext> result = stateManager.listAllWorkflows();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(redisTemplate).scan(any(ScanOptions.class));
        verify(redisTemplate, never()).keys(anyString());
    }

    @Test
    @DisplayName("listAllWorkflows 应正确遍历 SCAN 结果")
    void listAllWorkflows_shouldIterateScanResults() {
        Cursor<String> mockCursor = mock(Cursor.class);
        when(mockCursor.hasNext()).thenReturn(true, true, false);
        when(mockCursor.next()).thenReturn("contentops:workflow:wf-1", "contentops:workflow:wf-2");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(mockCursor);

        when(valueOps.get("contentops:workflow:wf-1"))
                .thenReturn("{\"workflowId\":\"wf-1\",\"status\":\"PENDING\"}");
        when(valueOps.get("contentops:workflow:wf-2"))
                .thenReturn("{\"workflowId\":\"wf-2\",\"status\":\"COMPLETED\"}");

        List<TaskContext> result = stateManager.listAllWorkflows();

        assertEquals(2, result.size());
    }

    // ════════════════ 分布式锁测试（P0-4） ════════════════

    @Test
    @DisplayName("executeWithLock 应获取并释放分布式锁")
    void executeWithLock_shouldAcquireAndReleaseLock() {
        String lockKey = "contentops:lock:workflow:wf-001";
        setupLockMock(lockKey);

        stateManager.executeWithLock("wf-001", wfId -> {
            // 模拟业务操作
        });

        verify(valueOps).setIfAbsent(eq(lockKey), anyString(), any(Duration.class));
        verify(redisTemplate).delete(lockKey);
    }

    @Test
    @DisplayName("updateWorkflowStateAtomically 应在锁保护下执行")
    void updateWorkflowStateAtomically_shouldUseLock() {
        String workflowId = "wf-atomic-001";
        String lockKey = "contentops:lock:workflow:" + workflowId;
        String stateKey = "contentops:workflow:" + workflowId;

        setupLockMock(lockKey);
        when(valueOps.get(stateKey))
                .thenReturn("{\"workflowId\":\"" + workflowId + "\",\"status\":\"PENDING\"}");

        stateManager.updateWorkflowStateAtomically(workflowId, ctx -> {
            ctx.setStatus(TaskStatus.IN_PROGRESS.name());
        });

        verify(valueOps).setIfAbsent(eq(lockKey), anyString(), any(Duration.class));
        verify(redisTemplate).delete(lockKey);
        verify(valueOps).set(eq(stateKey), anyString(), eq(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("mergeArtifacts 应原子合并产物")
    void mergeArtifacts_shouldMergeAtomically() {
        String workflowId = "wf-merge-001";
        String lockKey = "contentops:lock:workflow:" + workflowId;
        String stateKey = "contentops:workflow:" + workflowId;

        setupLockMock(lockKey);
        when(valueOps.get(stateKey))
                .thenReturn("{\"workflowId\":\"" + workflowId + "\","
                        + "\"status\":\"PENDING\","
                        + "\"accumulatedArtifacts\":{\"key1\":\"val1\"}}");

        Map<String, Object> newArtifacts = new HashMap<>();
        newArtifacts.put("key2", "val2");

        stateManager.mergeArtifacts(workflowId, newArtifacts);

        verify(valueOps).setIfAbsent(eq(lockKey), anyString(), any(Duration.class));
        verify(redisTemplate).delete(lockKey);
    }

    // ════════════════ 删除状态测试 ════════════════

    @Test
    @DisplayName("删除工作流状态应调用 Redis delete")
    void deleteWorkflowState_shouldCallRedisDelete() {
        stateManager.deleteWorkflowState("wf-to-delete");

        verify(redisTemplate).delete("contentops:workflow:wf-to-delete");
    }
}
