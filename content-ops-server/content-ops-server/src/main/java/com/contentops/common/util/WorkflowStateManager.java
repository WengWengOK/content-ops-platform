package com.contentops.common.util;

import com.contentops.common.dto.TaskContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 工作流状态管理（P0 升级：数据库持久化 + 可靠分布式锁）。
 *
 * <p><b>状态持久化</b>：TaskContext 以 JSON 存入 {@code contentops_workflow} 表
 * （H2 开发 / PostgreSQL 生产通用，schema 见 classpath:schema.sql）。
 * 此前仅存 Redis（24h TTL），重启即丢；现在数据库为唯一事实源。
 *
 * <p><b>分布式锁</b>（P0-3 修复）：
 * <ul>
 *   <li>Redis SETNX 锁增加<b>租约续期</b>（每 10s 续 30s，持有期间长任务不会锁过期）；
 *       解决 approveAndProceed/confirmSubStage 在锁内同步执行 LLM 调用时
 *       锁提前过期导致的并发竞态。</li>
 *   <li>Redis 不可用时降级为<b>进程内分片锁</b>，保证开发/演示环境可用。</li>
 * </ul>
 */
@Slf4j
public class WorkflowStateManager {

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 分布式锁 key 前缀 */
    private static final String LOCK_PREFIX = "contentops:lock:workflow:";
    /** 锁租约时长（秒） */
    private static final long LOCK_TIMEOUT_SECONDS = 30;
    /** 锁续期间隔（秒），远小于租约时长，保证长任务持锁不失效 */
    private static final long LOCK_RENEW_INTERVAL_SECONDS = 10;
    /** 获取锁的重试间隔（毫秒） */
    private static final long LOCK_RETRY_INTERVAL_MS = 100;
    /** 获取锁的最大重试次数 */
    private static final int LOCK_MAX_RETRIES = 50;

    /** 锁续期调度器（守护线程，不阻塞 JVM 退出） */
    private final ScheduledExecutorService lockRenewer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "workflow-lock-renewer");
                t.setDaemon(true);
                return t;
            });

    /** Redis 不可用时的进程内锁（按 workflowId 分片） */
    private final ConcurrentHashMap<String, Object> localLocks = new ConcurrentHashMap<>();

    private static final String SQL_UPDATE = """
            UPDATE contentops_workflow
               SET context_json = ?, owner_id = ?, updated_at = ?
             WHERE workflow_id = ?
            """;
    private static final String SQL_INSERT = """
            INSERT INTO contentops_workflow (workflow_id, context_json, owner_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String SQL_SELECT_BY_ID =
            "SELECT context_json FROM contentops_workflow WHERE workflow_id = ?";
    private static final String SQL_LIST =
            "SELECT context_json FROM contentops_workflow ORDER BY updated_at DESC";
    private static final String SQL_LIST_BY_OWNER =
            "SELECT context_json FROM contentops_workflow WHERE owner_id = ? ORDER BY updated_at DESC";
    private static final String SQL_DELETE =
            "DELETE FROM contentops_workflow WHERE workflow_id = ?";

    public WorkflowStateManager(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ════════════════ 状态持久化（数据库） ════════════════

    /**
     * 保存工作流状态（先 UPDATE，无行受影响则 INSERT，H2/PG 通用 upsert）。
     */
    public void saveWorkflowState(String workflowId, TaskContext context) {
        try {
            String json = objectMapper.writeValueAsString(context);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime createdAt = context.getCreatedAt() != null ? context.getCreatedAt() : now;
            LocalDateTime updatedAt = context.getUpdatedAt() != null ? context.getUpdatedAt() : now;
            int updated = jdbcTemplate.update(SQL_UPDATE, json, context.getOwnerId(), updatedAt, workflowId);
            if (updated == 0) {
                jdbcTemplate.update(SQL_INSERT, workflowId, json, context.getOwnerId(), createdAt, updatedAt);
            }
            log.debug("Saved workflow state (db) for: {}", workflowId);
        } catch (Exception e) {
            log.error("Failed to save workflow state (db): {}", workflowId, e);
        }
    }

    /**
     * 从数据库加载工作流状态。
     */
    public Optional<TaskContext> loadWorkflowState(String workflowId) {
        try {
            List<String> rows = jdbcTemplate.query(SQL_SELECT_BY_ID,
                    (rs, rowNum) -> rs.getString(1), workflowId);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(rows.get(0), TaskContext.class));
        } catch (Exception e) {
            log.error("Failed to load workflow state (db): {}", workflowId, e);
            return Optional.empty();
        }
    }

    /**
     * 列出全部工作流（按更新时间倒序）。
     */
    public List<TaskContext> listAllWorkflows() {
        return queryContexts(SQL_LIST);
    }

    /**
     * 列出指定用户的工作流（P0 数据隔离；ownerId 为 null 时回退全量）。
     */
    public List<TaskContext> listWorkflowsByOwner(String ownerId) {
        return queryContexts(SQL_LIST_BY_OWNER, ownerId);
    }

    /**
     * 删除工作流状态。
     */
    public void deleteWorkflowState(String workflowId) {
        try {
            jdbcTemplate.update(SQL_DELETE, workflowId);
        } catch (Exception e) {
            log.error("Failed to delete workflow state (db): {}", workflowId, e);
        }
    }

    private List<TaskContext> queryContexts(String sql, Object... args) {
        List<TaskContext> result = new ArrayList<>();
        try {
            // RowCallbackHandler 由 JdbcTemplate 逐行调用（已推进 rs.next()），
            // 回调内只处理当前行，勿再调用 rs.next()。
            jdbcTemplate.query(sql, rs -> {
                try {
                    result.add(objectMapper.readValue(rs.getString(1), TaskContext.class));
                } catch (Exception e) {
                    log.warn("Failed to deserialize workflow context", e);
                }
            }, args);
        } catch (Exception e) {
            log.error("Failed to list workflow states (db)", e);
        }
        return result;
    }

    // ════════════════ 分布式锁（Redis + 租约续期 + 进程内降级） ════════════════

    /**
     * 在锁保护下执行操作。
     *
     * <p>优先 Redis SETNX 锁（自动续期）；Redis 不可用时降级为进程内分片锁；
     * 锁被他人持有（竞争）且重试耗尽时抛出 {@link IllegalStateException}（保持原语义）。
     */
    public void executeWithLock(String workflowId, Consumer<String> action) {
        String lockKey = LOCK_PREFIX + workflowId;
        try {
            RedisLock redisLock = acquireRedisLock(lockKey, workflowId);
            try {
                action.accept(workflowId);
            } finally {
                releaseRedisLock(lockKey, redisLock);
            }
            return;
        } catch (IllegalStateException e) {
            // 锁竞争（Redis 可用但被他人持有）：与原先行为一致，向上抛
            throw e;
        } catch (Exception e) {
            log.warn("[WorkflowLock] Redis 锁不可用，降级为进程内锁 workflowId={}: {}",
                    workflowId, e.getMessage());
        }

        Object monitor = localLocks.computeIfAbsent(workflowId, k -> new Object());
        synchronized (monitor) {
            try {
                action.accept(workflowId);
            } finally {
                localLocks.remove(workflowId, monitor);
            }
        }
    }

    /**
     * 加载工作流状态并在锁保护下原子更新（read-modify-write）。
     */
    public void updateWorkflowStateAtomically(String workflowId, Consumer<TaskContext> updater) {
        executeWithLock(workflowId, wfId -> {
            Optional<TaskContext> stateOpt = loadWorkflowState(wfId);
            if (stateOpt.isPresent()) {
                TaskContext context = stateOpt.get();
                updater.accept(context);
                saveWorkflowState(wfId, context);
            } else {
                log.warn("Workflow state not found for atomic update: {}", wfId);
            }
        });
    }

    /**
     * 更新指定字段（锁保护）。
     */
    public void updateOutputs(String workflowId, java.util.Map<String, Object> outputs) {
        updateWorkflowStateAtomically(workflowId, context -> context.setOutputs(outputs));
    }

    /**
     * 合并产物（锁保护）。
     */
    public void mergeArtifacts(String workflowId, java.util.Map<String, Object> newArtifacts) {
        updateWorkflowStateAtomically(workflowId, context -> {
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            context.getAccumulatedArtifacts().putAll(newArtifacts);
        });
    }

    // ──────────────── Redis 锁实现（带租约续期） ────────────────

    /** 已获取的 Redis 锁（持有者标识 + 续期任务）。 */
    private record RedisLock(String key, String value, ScheduledFuture<?> renewTask) {
    }

    private RedisLock acquireRedisLock(String lockKey, String workflowId) {
        String lockValue = UUID.randomUUID().toString();
        for (int i = 0; i < LOCK_MAX_RETRIES; i++) {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(LOCK_TIMEOUT_SECONDS));
            if (Boolean.TRUE.equals(acquired)) {
                ScheduledFuture<?> renewTask = lockRenewer.scheduleAtFixedRate(
                        () -> renewLock(lockKey, lockValue),
                        LOCK_RENEW_INTERVAL_SECONDS,
                        LOCK_RENEW_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
                log.debug("[WorkflowLock] acquired with renewal: {}", workflowId);
                return new RedisLock(lockKey, lockValue, renewTask);
            }
            if (i < LOCK_MAX_RETRIES - 1) {
                try {
                    Thread.sleep(LOCK_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted while waiting for lock", e);
                }
            }
        }
        throw new IllegalStateException("Failed to acquire lock for workflow: " + workflowId
                + " after " + LOCK_MAX_RETRIES + " retries");
    }

    /**
     * 续期：仅当当前持有者仍是自己时才延长 TTL，避免覆盖他人锁。
     */
    private void renewLock(String lockKey, String lockValue) {
        try {
            String current = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(current)) {
                redisTemplate.opsForValue()
                        .set(lockKey, lockValue, Duration.ofSeconds(LOCK_TIMEOUT_SECONDS));
            }
        } catch (Exception e) {
            log.warn("[WorkflowLock] 续期失败（忽略，若 Redis 持续不可用将走进程内锁）: {}", e.getMessage());
        }
    }

    private void releaseRedisLock(String lockKey, RedisLock lock) {
        lock.renewTask().cancel(false);
        try {
            String current = redisTemplate.opsForValue().get(lockKey);
            if (lock.value().equals(current)) {
                redisTemplate.delete(lockKey);
            }
        } catch (Exception e) {
            log.warn("[WorkflowLock] 释放锁失败（依赖 TTL 自动过期）: {}", e.getMessage());
        }
    }

}
