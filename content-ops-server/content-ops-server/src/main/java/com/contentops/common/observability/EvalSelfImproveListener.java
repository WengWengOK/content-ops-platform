package com.contentops.common.observability;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.StageTransitionEvent;
import com.contentops.orchestrator.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 自我改进闭环（P2 #9 大厂特色：评测失败 → 自动触发优化迭代）。
 *
 * <p>监听终态 {@link StageTransitionEvent}（{@code STAGE_COMPLETED} 且
 * {@code toStage="COMPLETED"}），在 {@code gateEnabled=true} +
 * {@code selfImprove.autoOptimizationOnFail=true} 时：
 * <ol>
 *   <li>异步从 {@link LlmEvalRepository} 查询该 workflow 最近一次评测失败</li>
 *   <li>若 {@code judge_score ≤ triggerMaxScore}（严重不及格），
 *       且同一工作流累计自动触发次数 {@code < maxAutoOptimizations}</li>
 *   <li>通过 Redis/内存 幂等防重后，调用
 *       {@link WorkflowService#runStandaloneStage(String, AgentStage)} 以
 *       独立服务方式运行 {@code OPTIMIZATION} 阶段</li>
 * </ol>
 *
 * <h3>触发条件（全部同时满足才触发，缺一不触发）</h3>
 * <ul>
 *   <li>{@code contentops.evals.enabled=true}</li>
 *   <li>{@code contentops.evals.gateEnabled=true}</li>
 *   <li>{@code contentops.evals.self-improve.auto-optimization-on-fail=true}</li>
 *   <li>事件类型是 STAGE_COMPLETED 且 {@code toStage="COMPLETED"}</li>
 *   <li>contentops_llm_eval_run 中存在最新一条 passed=FALSE 且 score ≤ triggerMaxScore</li>
 *   <li>同一 workflow 自动触发次数 {@code < maxAutoOptimizations}（默认 1 次）</li>
 *   <li>幂等防重（Redis TTL 或 JVM 内存）1 小时内未触发过</li>
 * </ul>
 *
 * <p><b>所有判断/异常都只记日志，不阻断事件传播</b>（监听器仅为副作用，
 * 即便完全失败也不影响工作流正常完成事件的处理）。
 */
@Slf4j
@Component
public class EvalSelfImproveListener {

    /** WorkflowService.runStandaloneStage 的幂等计数（同一 workflow 最多触发几次）。 */
    private final ConcurrentMap<String, AtomicInteger> autoTriggerCount = new ConcurrentHashMap<>();

    /** 内存幂等标记（Redis 不可用时的 fallback）。 */
    private final ConcurrentMap<String, Long> localDedupUntilEpoch = new ConcurrentHashMap<>();

    private final EvalProperties properties;
    private final LlmEvalRepository evalRepository;
    private final WorkflowService workflowService;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public EvalSelfImproveListener(EvalProperties properties,
                                   LlmEvalRepository evalRepository,
                                   WorkflowService workflowService,
                                   @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.evalRepository = evalRepository;
        this.workflowService = workflowService;
        this.redisTemplate = redisTemplate;
        log.info("[SelfImprove] Agent自我改进闭环监听初始化: "
                        + "enabled={}, gateEnabled={}, autoOptOnFail={}, triggerMaxScore={}, maxAutoOpt={}",
                properties.isEnabled(), properties.isGateEnabled(),
                properties.getSelfImprove().isAutoOptimizationOnFail(),
                properties.getSelfImprove().getTriggerMaxScore(),
                properties.getSelfImprove().getMaxAutoOptimizations());
    }

    /**
     * 终态事件监听：只有 {@code STAGE_COMPLETED} 且 {@code toStage="COMPLETED"}
     * （即工作流已 COMPLETED）才考虑评测不通过的优化触发。
     */
    @EventListener
    public void onStageCompleted(StageTransitionEvent event) {
        if (!"STAGE_COMPLETED".equals(event.getEventType())
                || !"COMPLETED".equals(event.getToStage())) {
            return;
        }
        // 总开关（任一关闭都不浪费资源查询评测）
        EvalProperties.SelfImprove cfg = properties.getSelfImprove();
        if (!properties.isEnabled() || !properties.isGateEnabled() || !cfg.isAutoOptimizationOnFail()) {
            return;
        }
        // 异步执行：避免阻塞事件总线（STAGE_COMPLETED 被 SSE 广播使用）
        CompletableFuture.runAsync(() -> handleWorkflowCompleted(event.getWorkflowId(), cfg));
    }

    private void handleWorkflowCompleted(String workflowId, EvalProperties.SelfImprove cfg) {
        try {
            // 1. 查最近一次评测失败
            Map<String, Object> failing = evalRepository.findLatestFailingRun(workflowId)
                    .orElse(null);
            if (failing == null) {
                log.debug("[SelfImprove] workflowId={} 未发现评测失败记录，跳过", workflowId);
                return;
            }

            // 2. score 阈值判定（严重不及格才触发，避免噪声）
            Integer score = toInteger(failing.get("judge_score"));
            if (score != null && score > cfg.getTriggerMaxScore()) {
                log.info("[SelfImprove] workflowId={} 评测未达严重不及格阈值 score={} > trigger={}, 跳过",
                        workflowId, score, cfg.getTriggerMaxScore());
                return;
            }

            // 3. 次数限制（JVM 内存计数，防止 fail→opt→fail→opt 无限循环）
            int count = autoTriggerCount.computeIfAbsent(workflowId, k -> new AtomicInteger(0)).incrementAndGet();
            if (count > cfg.getMaxAutoOptimizations()) {
                log.warn("[SelfImprove] workflowId={} 已达到最大自动优化次数 {} 次，跳过",
                        workflowId, cfg.getMaxAutoOptimizations());
                return;
            }

            // 4. 幂等防重（优先 Redis，Redis 不可用退化为本地时间戳）
            if (!acquireDedupLock(workflowId, cfg)) {
                log.info("[SelfImprove] workflowId={} 已有自动优化触发记录（幂等防重），跳过", workflowId);
                return;
            }

            // 5. 触发优化（OptimizationAgent 独立阶段）
            String feedback = (failing.get("judge_feedback") instanceof String fb) ? fb : "";
            log.info("[SelfImprove] workflowId={} 评测不及格触发自动优化: score={}, stage={}, feedback={}",
                    workflowId, score, failing.get("stage"), feedback);
            workflowService.runStandaloneStage(workflowId, AgentStage.OPTIMIZATION);
        } catch (Exception e) {
            log.warn("[SelfImprove] workflowId={} 自我改进闭环触发失败: {}",
                    workflowId, e.getMessage());
        }
    }

    // ──────────────────── 工具方法 ────────────────────

    /**
     * 幂等防重：优先写入 Redis SETNX + TTL；Redis 不可用时使用本地 ConcurrentHashMap + 时间戳 TTL 兜底。
     *
     * @return true=获取到锁（首次），false=TTL 内已存在（应跳过触发）
     */
    private boolean acquireDedupLock(String workflowId, EvalProperties.SelfImprove cfg) {
        String key = "contentops:eval-auto-triggered:" + workflowId;
        Duration ttl = Duration.ofSeconds(Math.max(1, cfg.getDedupTtlSeconds()));
        if (redisTemplate != null) {
            try {
                Boolean set = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
                return Boolean.TRUE.equals(set);
            } catch (Exception e) {
                log.debug("[SelfImprove] Redis 幂等写入失败，降级 JVM 内存: {}", e.getMessage());
            }
        }
        // JVM 内存兜底
        long now = System.currentTimeMillis();
        long ttlMillis = ttl.toMillis();
        Long existing = localDedupUntilEpoch.putIfAbsent(key, now + ttlMillis);
        if (existing == null) {
            return true;
        }
        if (now > existing) {
            // 过期了，重置
            localDedupUntilEpoch.put(key, now + ttlMillis);
            return true;
        }
        return false;
    }

    private static Integer toInteger(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ignored) { return null; }
    }
}
