package com.contentops.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.TaskContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Manages workflow state in Redis.
 * Each workflow's state is stored as a JSON-serialized TaskContext.
 *
 * <p><b>P0 修复：</b>
 * <ul>
 *   <li>使用 {@code SCAN} 替代 {@code KEYS} 命令，避免 O(N) 阻塞 Redis</li>
 *   <li>引入分布式锁 (SETNX) 保护状态读写，防止并发竞态条件</li>
 * </ul>
 */
@Slf4j
public class WorkflowStateManager {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 分布式锁 key 前缀 */
    private static final String LOCK_PREFIX = "contentops:lock:workflow:";
    /** 分布式锁默认超时（秒），防止死锁 */
    private static final long LOCK_TIMEOUT_SECONDS = 30;
    /** 获取锁的重试间隔（毫秒） */
    private static final long LOCK_RETRY_INTERVAL_MS = 100;
    /** 获取锁的最大重试次数 */
    private static final int LOCK_MAX_RETRIES = 50;
    /** SCAN 每批返回数量 */
    private static final int SCAN_BATCH_SIZE = 100;

    public WorkflowStateManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ════════════════ 分布式锁 ════════════════

    /**
     * 尝试获取分布式锁。
     *
     * <p>使用 Redis {@code SET key value NX EX} 原语保证原子性。
     * 锁值使用 UUID 标识持有者，释放时校验避免误删。
     *
     * @param workflowId 工作流 ID
     * @return 锁标识 (lockValue)，获取失败返回 null
     */
    private String tryAcquireLock(String workflowId) {
        String lockKey = LOCK_PREFIX + workflowId;
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(LOCK_TIMEOUT_SECONDS));
        if (Boolean.TRUE.equals(acquired)) {
            return lockValue;
        }
        return null;
    }

    /**
     * 获取分布式锁，带重试。
     *
     * @param workflowId 工作流 ID
     * @return 锁标识 (lockValue)
     * @throws IllegalStateException 如果在最大重试次数内未能获取锁
     */
    private String acquireLockWithRetry(String workflowId) {
        for (int i = 0; i < LOCK_MAX_RETRIES; i++) {
            String lockValue = tryAcquireLock(workflowId);
            if (lockValue != null) {
                return lockValue;
            }
            try {
                Thread.sleep(LOCK_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Thread interrupted while waiting for lock", e);
            }
        }
        throw new IllegalStateException("Failed to acquire lock for workflow: " + workflowId
                + " after " + LOCK_MAX_RETRIES + " retries");
    }

    /**
     * 释放分布式锁（校验 lockValue 防止误删）。
     */
    private void releaseLock(String workflowId, String lockValue) {
        String lockKey = LOCK_PREFIX + workflowId;
        try {
            String currentValue = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(currentValue)) {
                redisTemplate.delete(lockKey);
            }
        } catch (Exception e) {
            log.warn("Failed to release lock for workflow: {}", workflowId, e);
        }
    }

    /**
     * 在分布式锁保护下执行操作。
     *
     * @param workflowId 工作流 ID
     * @param action 需要在锁保护下执行的操作
     */
    public void executeWithLock(String workflowId, Consumer<String> action) {
        String lockValue = acquireLockWithRetry(workflowId);
        try {
            action.accept(workflowId);
        } finally {
            releaseLock(workflowId, lockValue);
        }
    }

    // ════════════════ 状态读写 ════════════════

    /**
     * Save workflow state to Redis.
     */
    public void saveWorkflowState(String workflowId, TaskContext context) {
        try {
            String key = AgentConstants.WORKFLOW_STATE_PREFIX + workflowId;
            String json = objectMapper.writeValueAsString(context);
            redisTemplate.opsForValue().set(key, json, Duration.ofHours(24));
            log.debug("Saved workflow state for: {}", workflowId);
        } catch (Exception e) {
            log.error("Failed to save workflow state: {}", workflowId, e);
        }
    }

    /**
     * 加载工作流状态并在锁保护下更新。
     *
     * <p>防止并发更新导致的 lost-update 竞态：
     * 多个线程同时 load → modify → save 时，后写的会覆盖先写的。
     * 此方法使用分布式锁保证 read-modify-write 的原子性。
     *
     * @param workflowId 工作流 ID
     * @param updater 对 TaskContext 的更新函数
     */
    public void updateWorkflowStateAtomically(String workflowId, java.util.function.Consumer<TaskContext> updater) {
        String lockValue = acquireLockWithRetry(workflowId);
        try {
            Optional<TaskContext> stateOpt = loadWorkflowState(workflowId);
            if (stateOpt.isPresent()) {
                TaskContext context = stateOpt.get();
                updater.accept(context);
                saveWorkflowState(workflowId, context);
            } else {
                log.warn("Workflow state not found for atomic update: {}", workflowId);
            }
        } finally {
            releaseLock(workflowId, lockValue);
        }
    }

    /**
     * Load workflow state from Redis.
     */
    public Optional<TaskContext> loadWorkflowState(String workflowId) {
        try {
            String key = AgentConstants.WORKFLOW_STATE_PREFIX + workflowId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, TaskContext.class));
        } catch (Exception e) {
            log.error("Failed to load workflow state: {}", workflowId, e);
            return Optional.empty();
        }
    }

    /**
     * Update a specific field in the workflow state.
     * <p>使用分布式锁保护 read-modify-write 操作。
     */
    public void updateOutputs(String workflowId, java.util.Map<String, Object> outputs) {
        updateWorkflowStateAtomically(workflowId, context -> context.setOutputs(outputs));
    }

    /**
     * Merge new artifacts into the accumulated artifacts.
     * <p>使用分布式锁保护 read-modify-write 操作。
     */
    public void mergeArtifacts(String workflowId, java.util.Map<String, Object> newArtifacts) {
        updateWorkflowStateAtomically(workflowId, context -> {
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            context.getAccumulatedArtifacts().putAll(newArtifacts);
        });
    }

    /**
     * Delete workflow state.
     */
    public void deleteWorkflowState(String workflowId) {
        String key = AgentConstants.WORKFLOW_STATE_PREFIX + workflowId;
        redisTemplate.delete(key);
    }

    /**
     * List all workflow states from Redis.
     *
     * <p><b>P0 修复：</b>使用 {@code SCAN} 命令替代 {@code KEYS}，
     * 避免在大数据量下阻塞 Redis。SCAN 是增量式遍历，时间复杂度 O(1) 每次调用。
     *
     * @return list of all stored TaskContext objects (may be empty if none exist)
     */
    public List<TaskContext> listAllWorkflows() {
        List<TaskContext> workflows = new ArrayList<>();
        try {
            String pattern = AgentConstants.WORKFLOW_STATE_PREFIX + "*";
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(SCAN_BATCH_SIZE)
                    .build();

            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    try {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            TaskContext ctx = objectMapper.readValue(json, TaskContext.class);
                            workflows.add(ctx);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to deserialize workflow state for key: {}", key, e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to list all workflow states", e);
        }
        return workflows;
    }
}
