package com.contentops.common.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 流式配额预扣器（Streaming Quota Pre-Deductor）。
 *
 * <p>解决「streaming 模式下事后扣减配额导致预算被烧光」的核心问题：在流式生成开始前预扣估算配额，
 * 流式结束后对账多退少补，从而把「可用额度」的强一致性前移到请求入口，杜绝滞后超卖。
 *
 * <h3>背景（生产必考 / 面试洞察）</h3>
 * <p>某企业网关在 streaming 模式下采用<b>事后扣减</b>配额：客服会话挂 30 秒，token 边产生边吐，
 * 网关请求返回后才拿到真实 usage 去扣配额。慢请求堆积期间新请求源源不断，配额计数器永远滞后于
 * 真实消耗 —— 结果 2 小时内烧光全公司月预算。根因：流式场景下「先消费、后记账」打破了配额的
 * 强一致性，计数器永远追赶不上真实消耗。
 *
 * <h3>解决方案：预扣 + 流式对账（pre-deduction + streaming reconciliation）</h3>
 * <ul>
 *   <li><b>预扣</b>：streaming 开始<b>之前</b>，按 max_tokens 配置预扣一笔估算配额，把「可用额度」
 *       提前锁定，杜绝滞后超卖。</li>
 *   <li><b>对账</b>：streaming 结束后，比较估算值与真实 usage，多退少补（refund excess / charge deficit）。</li>
 * </ul>
 *
 * <h3>软/硬约束分离（soft / hard constraint separation）</h3>
 * <ul>
 *   <li><b>软约束</b>（限流 RPM/TPM）= 最终一致性，允许略微超限 —— 用于保护上游 API，不涉及计费。
 *       本类对软约束采用「超额容忍因子」({@link #SOFT_OVERAGE_FACTOR})，允许在限值附近小幅越界。</li>
 *   <li><b>硬约束</b>（预算配额 daily/monthly）= 严格 + 计费，绝不能超卖 —— 采用「Lua 预扣 + 流式对账」
 *       保证原子性与准确性。本类用 {@code synchronized} 单计数器原子操作<b>模拟</b> Lua 原子性，
 *       生产环境应以 Redis Lua 脚本实现跨实例原子预扣，参考 {@link #LUA_PRE_DEDUCT_SCRIPT}。</li>
 * </ul>
 *
 * <h3>三级配额架构（three-level quota architecture）</h3>
 * <ol>
 *   <li><b>模型 API 级</b>（TPM, Tokens Per Minute）—— {@code modelId -> TokenCounter}，
 *       每分钟重置窗口。</li>
 *   <li><b>Agent 级</b>（RPM, Requests Per Minute）—— {@code agentId -> RequestCounter}，
 *       每分钟重置窗口。</li>
 *   <li><b>租户级</b>（按订阅档位，daily/monthly 预算）—— {@code tenantId -> BudgetCounter}，
 *       按天或按月重置窗口，与计费挂钩。</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 1. streaming 开始前预扣
 * QuotaRequest req = new QuotaRequest("tenant-001", "gpt-4o", "agent-writer", 4096, QuotaType.HARD);
 * PreDeductionResult result = preDeductor.preDeduct(req);
 * if (!result.approved()) {
 *     throw new BusinessException(ErrorCode.QUOTA_EXCEEDED, result.deniedReason());
 * }
 * // 2. 执行 streaming ... 拿到真实 token
 * // 3. streaming 结束后对账
 * ReconciliationResult recon = preDeductor.reconcile(result.requestId(), actualTokens);
 * }</pre>
 *
 * <p><b>线程安全</b>：三级计数器均基于 {@link ConcurrentHashMap}；单计数器的「检查-扣减」复合操作通过
 * {@code synchronized} 保证原子性（模拟 Redis Lua）。预扣阶段采用补偿事务（acquire + rollback）跨三级回滚，
 * 任一级失败则逆向退还已扣减的额度。
 *
 * <p><b>实现说明</b>：本类为单机内存实现（demo / 单体）。生产环境应替换为 Redis + Lua 脚本以获得跨实例
 * 原子性与持久化，并辅以 TTL 清理未对账的 pending 记录，防止内存泄漏。
 *
 * @see TokenMetricsService
 * @see TokenEstimator
 */
@Slf4j
@Component
public class StreamingQuotaPreDeductor {

    // ──────────────────────── 常量 ────────────────────────

    /** 一分钟窗口（毫秒），用于 TPM / RPM 重置。 */
    private static final long MINUTE_MILLIS = 60_000L;

    /** 默认模型 TPM 上限（tokens / minute）。生产环境应从模型配置读取。 */
    private static final int DEFAULT_MODEL_TPM = 100_000;

    /** 默认 Agent RPM 上限（requests / minute）。 */
    private static final int DEFAULT_AGENT_RPM = 600;

    /** 平台级聚合 RPM 上限（checkQuota 预览时跨所有 Agent 汇总判定）。 */
    private static final int DEFAULT_PLATFORM_RPM = 6_000;

    /** 默认租户预算上限（tokens）。 */
    private static final int DEFAULT_TENANT_BUDGET = 2_000_000;

    /** 默认预算周期。 */
    private static final BudgetPeriod DEFAULT_BUDGET_PERIOD = BudgetPeriod.DAILY;

    /** 软约束超额容忍因子：允许在限值的 1.5 倍以内放行（最终一致性，可略微超限）。 */
    private static final double SOFT_OVERAGE_FACTOR = 1.5;

    /**
     * 生产环境 Redis Lua 预扣脚本（参考实现）。
     *
     * <p>原子地执行「检查预算 → 预扣 → 记录 pending」，保证分布式下不会超卖：
     * <pre>{@code
     * KEYS[1] = tenant budget key (string, 已用额度)
     * KEYS[2] = pending deductions hash key
     * ARGV[1] = requestId
     * ARGV[2] = estimatedTokens
     * ARGV[3] = budgetLimit
     * }</pre>
     * 返回 1 表示预扣成功，0 表示预算不足。
     */
    private static final String LUA_PRE_DEDUCT_SCRIPT = """
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            local limit = tonumber(ARGV[3])
            local estimate = tonumber(ARGV[2])
            if used + estimate > limit then
                return 0
            end
            redis.call('INCRBY', KEYS[1], estimate)
            redis.call('HSET', KEYS[2], ARGV[1], estimate)
            return 1
            """;

    // ──────────────────────── 三级计数器 ────────────────────────

    /** 模型 API 级：{@code modelId -> TokenCounter}（TPM）。 */
    private final ConcurrentHashMap<String, TokenCounter> modelCounters = new ConcurrentHashMap<>();

    /** Agent 级：{@code agentId -> RequestCounter}（RPM）。 */
    private final ConcurrentHashMap<String, RequestCounter> agentCounters = new ConcurrentHashMap<>();

    /** 租户级：{@code tenantId -> BudgetCounter}（daily/monthly 预算）。 */
    private final ConcurrentHashMap<String, BudgetCounter> tenantCounters = new ConcurrentHashMap<>();

    /** 待对账的预扣记录：{@code requestId -> PendingDeduction}，streaming 完成后据此对账。 */
    private final ConcurrentHashMap<String, PendingDeduction> pendingDeductions = new ConcurrentHashMap<>();

    // ──────────────────────── 公共 API ────────────────────────

    /**
     * 流式配额预扣：在 streaming 开始前预扣估算配额。
     *
     * <p>执行流程：
     * <ol>
     *   <li>生成唯一 requestId；</li>
     *   <li>按 {@link QuotaType} 分流软/硬约束执行：
     *     <ul>
     *       <li>{@link QuotaType#HARD}：严格预扣租户预算（模拟 Lua 原子），再扣模型 TPM、Agent RPM，
     *           任一级失败则补偿事务回滚已扣额度；</li>
     *       <li>{@link QuotaType#SOFT}：仅以超额容忍因子扣减模型 TPM、Agent RPM，不触碰计费预算；</li>
     *     </ul></li>
     *   <li>登记 pending 记录，等待 {@link #reconcile} 对账。</li>
     * </ol>
     *
     * @param request 配额请求（租户 / 模型 / Agent / 估算 token / 软硬类型）
     * @return 预扣结果（是否放行、预扣额度、requestId、拒绝原因）
     */
    public PreDeductionResult preDeduct(QuotaRequest request) {
        Objects.requireNonNull(request, "QuotaRequest 不能为 null");
        String requestId = generateRequestId();
        long now = System.currentTimeMillis();

        // 严格预览（用于日志观测；实际放行以预扣阶段的原子 acquire 为准）
        QuotaCheckResult preview = checkQuota(request.tenantId(), request.modelId(), request.estimatedTokens());
        log.debug("[QuotaPreDeduct] preview requestId={} tenant={} model={} agent={} => {}",
                requestId, request.tenantId(), request.modelId(), request.agentId(), preview);

        return switch (request.type()) {
            case HARD -> preDeductHard(request, requestId, now);
            case SOFT -> preDeductSoft(request, requestId, now);
        };
    }

    /**
     * 流式对账：streaming 结束后，比较估算值与真实 usage，多退少补。
     *
     * <p>对账规则：
     * <ul>
     *   <li>{@code actual < preDeducted}：退还超额（refund excess）—— 把多扣的额度还回相应计数器；</li>
     *   <li>{@code actual > preDeducted}：补扣差额（charge deficit）—— 真实 token 已被消费，强制入账
     *       （预算可能因此略微超限，属计费准确性优先，成本已实际发生）。</li>
     * </ul>
     *
     * <p>返回的 {@code refundOrCharge} 为带符号差额 {@code actual - preDeducted}：
     * 正值表示补扣（实际超预扣），负值表示退还（预扣超额）。
     *
     * @param requestId     {@link #preDeduct} 返回的 requestId
     * @param actualTokens  streaming 结束后的真实 token 消耗
     * @return 对账结果（preDeducted / actual / 差额 / 对账后余额）
     */
    public ReconciliationResult reconcile(String requestId, int actualTokens) {
        Objects.requireNonNull(requestId, "requestId 不能为 null");
        long now = System.currentTimeMillis();

        PendingDeduction pending = pendingDeductions.remove(requestId);
        if (pending == null) {
            log.warn("[QuotaReconcile] 未找到 pending 预扣记录，requestId={}, actualTokens={}, 跳过对账",
                    requestId, actualTokens);
            return new ReconciliationResult(requestId, 0, actualTokens, 0, 0);
        }

        int preDeducted = pending.estimatedTokens();
        int delta = actualTokens - preDeducted; // 正=补扣, 负=退还
        int newBalance;

        if (pending.type() == QuotaType.HARD) {
            BudgetCounter budget = tenantCounter(pending.tenantId());
            TokenCounter tpm = modelCounter(pending.modelId());
            // 预算：多退少补（补扣强制入账，保证计费准确）
            if (delta < 0) {
                budget.refund(-delta, now);
            } else if (delta > 0) {
                budget.forceAdd(delta, now);
            }
            // 模型 TPM 同步校正（限流计数器准确性）
            if (delta < 0) {
                tpm.refund(-delta, now);
            } else if (delta > 0) {
                tpm.forceAdd(delta, now);
            }
            newBalance = budget.remaining(now);
        } else {
            // SOFT：仅校正模型 TPM 限流计数器，不涉及计费预算
            TokenCounter tpm = modelCounter(pending.modelId());
            if (delta < 0) {
                tpm.refund(-delta, now);
            } else if (delta > 0) {
                tpm.forceAdd(delta, now);
            }
            newBalance = tpm.remaining(now);
        }

        log.info("[QuotaReconcile] requestId={} type={} preDeducted={} actual={} delta={} newBalance={}",
                requestId, pending.type(), preDeducted, actualTokens, delta, newBalance);

        return new ReconciliationResult(requestId, preDeducted, actualTokens, delta, newBalance);
    }

    /**
     * 三级配额预检（只读预览，不扣减）：检查模型 TPM、Agent RPM、租户预算三级是否都有余量。
     *
     * <p>注意：本方法为<b>严格</b>预览（按限值判定，不含软约束超额容忍）。
     * Agent 级因无具体 agentId，按<b>平台聚合 RPM</b>（所有 Agent 计数器之和）判定。
     * {@link #deniedLevel()} 返回优先级最高的失败级别（租户 > 模型 > Agent），全部通过时为 {@code null}。
     *
     * @param tenantId       租户 ID
     * @param modelId        模型 ID
     * @param estimatedTokens 估算 token 数
     * @return 三级检查结果
     */
    public QuotaCheckResult checkQuota(String tenantId, String modelId, int estimatedTokens) {
        long now = System.currentTimeMillis();

        boolean modelOk = modelCounter(modelId).peek(estimatedTokens, now);
        boolean tenantOk = tenantCounter(tenantId).peek(estimatedTokens, now);
        boolean agentOk = peekAgentAggregateRpm(now);

        // 拒绝级别优先级：租户（最硬）> 模型 > Agent
        String deniedLevel = null;
        if (!tenantOk) {
            deniedLevel = "TENANT";
        } else if (!modelOk) {
            deniedLevel = "MODEL";
        } else if (!agentOk) {
            deniedLevel = "AGENT";
        }

        return new QuotaCheckResult(modelOk, agentOk, tenantOk, deniedLevel);
    }

    /**
     * 三级计数器快照（诊断 / 可观测性）。
     *
     * @return 各级计数器当前状态的换行分隔字符串
     */
    public String snapshot() {
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        lines.add("== Model TPM ==");
        modelCounters.forEach((k, v) -> lines.add(k + " => " + describeCounter(v, now)));
        lines.add("== Agent RPM ==");
        agentCounters.forEach((k, v) -> lines.add(k + " => " + describeCounter(v, now)));
        lines.add("== Tenant Budget ==");
        tenantCounters.forEach((k, v) -> lines.add(k + " => " + describeCounter(v, now)));
        lines.add("== Pending ==");
        lines.add("count=" + pendingDeductions.size());
        return String.join("\n", lines);
    }

    // ──────────────────────── 预扣实现（软/硬分流） ────────────────────────

    /**
     * 硬约束预扣：严格预扣租户预算 + 模型 TPM + Agent RPM，补偿事务回滚。
     * <p>扣减顺序：租户预算 → 模型 TPM → Agent RPM；任一级失败则逆向回滚已扣额度。
     */
    private PreDeductionResult preDeductHard(QuotaRequest req, String requestId, long now) {
        BudgetCounter budget = tenantCounter(req.tenantId());
        TokenCounter tpm = modelCounter(req.modelId());
        RequestCounter rpm = agentCounter(req.agentId());
        int est = req.estimatedTokens();

        if (!budget.tryAcquire(est, now)) {
            return deny(requestId, "TENANT_BUDGET_INSUFFICIENT", now, req);
        }
        if (!tpm.tryAcquire(est, now)) {
            budget.refund(est, now); // 回滚预算
            return deny(requestId, "MODEL_TPM_INSUFFICIENT", now, req);
        }
        if (!rpm.tryAcquire(1, now)) {
            tpm.refund(est, now);     // 回滚 TPM
            budget.refund(est, now);  // 回滚预算
            return deny(requestId, "AGENT_RPM_INSUFFICIENT", now, req);
        }

        recordPending(requestId, req, now);
        log.info("[QuotaPreDeduct] APPROVED(HARD) requestId={} tenant={} model={} agent={} preDeducted={}",
                requestId, req.tenantId(), req.modelId(), req.agentId(), est);
        return new PreDeductionResult(true, est, requestId, null, now);
    }

    /**
     * 软约束预扣：仅以超额容忍因子扣减模型 TPM + Agent RPM，不触碰计费预算（最终一致性）。
     */
    private PreDeductionResult preDeductSoft(QuotaRequest req, String requestId, long now) {
        TokenCounter tpm = modelCounter(req.modelId());
        RequestCounter rpm = agentCounter(req.agentId());
        int est = req.estimatedTokens();

        if (!tpm.tryAcquire(est, now, SOFT_OVERAGE_FACTOR)) {
            return deny(requestId, "MODEL_TPM_INSUFFICIENT", now, req);
        }
        if (!rpm.tryAcquire(1, now, SOFT_OVERAGE_FACTOR)) {
            tpm.refund(est, now); // 回滚 TPM
            return deny(requestId, "AGENT_RPM_INSUFFICIENT", now, req);
        }

        recordPending(requestId, req, now);
        log.info("[QuotaPreDeduct] APPROVED(SOFT) requestId={} tenant={} model={} agent={} preDeducted={}",
                requestId, req.tenantId(), req.modelId(), req.agentId(), est);
        return new PreDeductionResult(true, est, requestId, null, now);
    }

    /** 构造拒绝结果并记录日志。 */
    private PreDeductionResult deny(String requestId, String reason, long now, QuotaRequest req) {
        log.warn("[QuotaPreDeduct] DENIED requestId={} type={} tenant={} model={} agent={} reason={}",
                requestId, req.type(), req.tenantId(), req.modelId(), req.agentId(), reason);
        return new PreDeductionResult(false, 0, requestId, reason, now);
    }

    /** 登记待对账的预扣记录。 */
    private void recordPending(String requestId, QuotaRequest req, long now) {
        pendingDeductions.put(requestId, new PendingDeduction(
                requestId, req.tenantId(), req.modelId(), req.agentId(),
                req.estimatedTokens(), req.type(), now));
    }

    // ──────────────────────── 计数器工厂 ────────────────────────

    private TokenCounter modelCounter(String modelId) {
        return modelCounters.computeIfAbsent(modelId,
                k -> new TokenCounter(DEFAULT_MODEL_TPM, System.currentTimeMillis()));
    }

    private RequestCounter agentCounter(String agentId) {
        return agentCounters.computeIfAbsent(agentId,
                k -> new RequestCounter(DEFAULT_AGENT_RPM, System.currentTimeMillis()));
    }

    private BudgetCounter tenantCounter(String tenantId) {
        return tenantCounters.computeIfAbsent(tenantId,
                k -> new BudgetCounter(DEFAULT_TENANT_BUDGET, DEFAULT_BUDGET_PERIOD, System.currentTimeMillis()));
    }

    /** 平台聚合 RPM 预览：所有 Agent 计数器当前请求数之和是否还有 1 个余量。 */
    private boolean peekAgentAggregateRpm(long now) {
        if (agentCounters.isEmpty()) {
            return true;
        }
        int total = 0;
        for (RequestCounter rc : agentCounters.values()) {
            total += rc.currentUsage(now);
        }
        return total + 1 <= DEFAULT_PLATFORM_RPM;
    }

    /** 生成预扣 requestId。 */
    private String generateRequestId() {
        return "pd-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 基于密封层次结构的模式匹配：描述单个计数器状态。
     * <p>展示 Java 21 pattern matching switch over sealed interface（穷尽匹配，无需 default）。
     */
    private String describeCounter(QuotaCounter counter, long now) {
        return switch (counter) {
            case TokenCounter tc ->
                    "TPM[limit=%d,used=%d,remaining=%d]".formatted(tc.limit(), tc.currentUsage(now), tc.remaining(now));
            case RequestCounter rc ->
                    "RPM[limit=%d,used=%d,remaining=%d]".formatted(rc.limit(), rc.currentUsage(now), rc.remaining(now));
            case BudgetCounter bc ->
                    "BUDGET[limit=%d,used=%d,remaining=%d,period=%s]".formatted(
                            bc.limit(), bc.currentUsage(now), bc.remaining(now), bc.period());
        };
    }

    // ──────────────────────── 数据载体（records / enum） ────────────────────────

    /**
     * 配额类型：软约束（限流）/ 硬约束（预算计费）。
     */
    public enum QuotaType {
        /** 软约束：限流（RPM/TPM），最终一致性，允许略微超限，不涉及计费。 */
        SOFT,
        /** 硬约束：预算配额（daily/monthly），严格预扣 + 计费 + 流式对账，绝不能超卖。 */
        HARD
    }

    /**
     * 配额预扣请求。
     *
     * @param tenantId       租户 ID
     * @param modelId        模型 ID
     * @param agentId        Agent ID
     * @param estimatedTokens 估算 token 数（通常取自 max_tokens 配置）
     * @param type           软/硬约束类型
     */
    public record QuotaRequest(
            String tenantId,
            String modelId,
            String agentId,
            int estimatedTokens,
            QuotaType type
    ) {
        /** 紧凑构造器：校验必填字段。 */
        public QuotaRequest {
            Objects.requireNonNull(tenantId, "tenantId 不能为 null");
            Objects.requireNonNull(modelId, "modelId 不能为 null");
            Objects.requireNonNull(agentId, "agentId 不能为 null");
            Objects.requireNonNull(type, "type 不能为 null");
            if (estimatedTokens < 0) {
                throw new IllegalArgumentException("estimatedTokens 不能为负: " + estimatedTokens);
            }
        }
    }

    /**
     * 预扣结果。
     *
     * @param approved          是否放行
     * @param preDeductedAmount 实际预扣额度（拒绝时为 0）
     * @param requestId         预扣请求 ID（对账时回传）
     * @param deniedReason      拒绝原因（放行时为 null）
     * @param timestamp         预扣时间戳（毫秒）
     */
    public record PreDeductionResult(
            boolean approved,
            int preDeductedAmount,
            String requestId,
            String deniedReason,
            long timestamp
    ) {
    }

    /**
     * 三级配额检查结果。
     *
     * @param modelLevelOk  模型级（TPM）是否通过
     * @param agentLevelOk  Agent 级（聚合 RPM）是否通过
     * @param tenantLevelOk 租户级（预算）是否通过
     * @param deniedLevel   拒绝级别（"TENANT"/"MODEL"/"AGENT"），全部通过时为 null
     */
    public record QuotaCheckResult(
            boolean modelLevelOk,
            boolean agentLevelOk,
            boolean tenantLevelOk,
            String deniedLevel
    ) {
    }

    /**
     * 对账结果。
     *
     * @param requestId       预扣请求 ID
     * @param preDeducted     预扣额度（估算值）
     * @param actual          真实消耗
     * @param refundOrCharge  带符号差额 {@code actual - preDeducted}：正值=补扣（实际超预扣），
     *                        负值=退还（预扣超额）；对 HARD 针对 tenant 预算，对 SOFT 针对 model TPM
     * @param newBalance      对账后余额（HARD=租户预算余额，SOFT=模型 TPM 余额）
     */
    public record ReconciliationResult(
            String requestId,
            int preDeducted,
            int actual,
            int refundOrCharge,
            int newBalance
    ) {
    }

    /** 待对账的预扣记录（内部）。 */
    private record PendingDeduction(
            String requestId,
            String tenantId,
            String modelId,
            String agentId,
            int estimatedTokens,
            QuotaType type,
            long timestamp
    ) {
    }

    // ──────────────────────── 计数器（sealed 层次结构） ────────────────────────

    /**
     * 配额计数器密封接口：统一三级计数器的窗口/重置/读取语义。
     * <p>由 {@link TokenCounter}、{@link RequestCounter}、{@link BudgetCounter} 实现，
     * 配合 {@link #describeCounter} 演示 Java 21 密封类 + 模式匹配。
     */
    private sealed interface QuotaCounter permits TokenCounter, RequestCounter, BudgetCounter {
        /** 计数上限。 */
        int limit();

        /** 当前窗口起始时间戳。 */
        long windowStartMillis();

        /** 若窗口已过期则重置计数。 */
        void resetIfExpired(long now);

        /** 当前窗口已用量。 */
        int currentUsage(long now);

        /** 当前窗口剩余量。 */
        int remaining(long now);
    }

    /**
     * Token 计数器（TPM - Tokens Per Minute），模型 API 级限流。
     * <p>每分钟重置窗口。「检查-扣减」复合操作通过 {@code synchronized} 保证原子性（模拟 Lua）。
     */
    private static final class TokenCounter implements QuotaCounter {
        private final int limit;
        private final AtomicInteger current = new AtomicInteger(0);
        private final AtomicLong windowStart;

        TokenCounter(int limit, long now) {
            this.limit = limit;
            this.windowStart = new AtomicLong(now);
        }

        /** 严格扣减（按限值判定）。 */
        synchronized boolean tryAcquire(int tokens, long now) {
            return tryAcquire(tokens, now, 1.0);
        }

        /** 按超额容忍因子扣减（软约束用）。 */
        synchronized boolean tryAcquire(int tokens, long now, double overageFactor) {
            resetIfExpired(now);
            int effectiveLimit = (int) Math.ceil(limit * overageFactor);
            if (current.get() + tokens > effectiveLimit) {
                return false;
            }
            current.addAndGet(tokens);
            return true;
        }

        /** 退还 token（对账退差）。 */
        synchronized void refund(int tokens, long now) {
            resetIfExpired(now);
            current.updateAndGet(v -> Math.max(0, v - tokens));
        }

        /** 强制追加（对账补差，真实 token 已消费，保证计费/限流准确性，可能略微超限）。 */
        synchronized void forceAdd(int tokens, long now) {
            resetIfExpired(now);
            current.addAndGet(tokens);
        }

        /** 只读预检（不扣减）。 */
        synchronized boolean peek(int tokens, long now) {
            resetIfExpired(now);
            return current.get() + tokens <= limit;
        }

        @Override
        public synchronized void resetIfExpired(long now) {
            if (now - windowStart.get() >= MINUTE_MILLIS) {
                current.set(0);
                windowStart.set(now);
            }
        }

        @Override
        public synchronized int currentUsage(long now) {
            resetIfExpired(now);
            return current.get();
        }

        @Override
        public int remaining(long now) {
            return Math.max(0, limit - currentUsage(now));
        }

        @Override
        public int limit() {
            return limit;
        }

        @Override
        public long windowStartMillis() {
            return windowStart.get();
        }
    }

    /**
     * 请求计数器（RPM - Requests Per Minute），Agent 级限流。
     * <p>每分钟重置窗口。计数单位为「请求数」而非 token。
     */
    private static final class RequestCounter implements QuotaCounter {
        private final int limit;
        private final AtomicInteger current = new AtomicInteger(0);
        private final AtomicLong windowStart;

        RequestCounter(int limit, long now) {
            this.limit = limit;
            this.windowStart = new AtomicLong(now);
        }

        /** 严格扣减请求数。 */
        synchronized boolean tryAcquire(int requests, long now) {
            return tryAcquire(requests, now, 1.0);
        }

        /** 按超额容忍因子扣减请求数（软约束用）。 */
        synchronized boolean tryAcquire(int requests, long now, double overageFactor) {
            resetIfExpired(now);
            int effectiveLimit = (int) Math.ceil(limit * overageFactor);
            if (current.get() + requests > effectiveLimit) {
                return false;
            }
            current.addAndGet(requests);
            return true;
        }

        /** 退还请求数（预扣回滚用）。 */
        synchronized void refund(int requests, long now) {
            resetIfExpired(now);
            current.updateAndGet(v -> Math.max(0, v - requests));
        }

        @Override
        public synchronized void resetIfExpired(long now) {
            if (now - windowStart.get() >= MINUTE_MILLIS) {
                current.set(0);
                windowStart.set(now);
            }
        }

        @Override
        public synchronized int currentUsage(long now) {
            resetIfExpired(now);
            return current.get();
        }

        @Override
        public int remaining(long now) {
            return Math.max(0, limit - currentUsage(now));
        }

        @Override
        public int limit() {
            return limit;
        }

        @Override
        public long windowStartMillis() {
            return windowStart.get();
        }
    }

    /**
     * 预算计数器（daily/monthly），租户级硬约束 + 计费。
     * <p>按天或按月重置窗口。预扣必须严格不超限（{@link #tryAcquire}），
     * 对账补差用 {@link #forceAdd} 强制入账（成本已实际发生）。
     */
    private static final class BudgetCounter implements QuotaCounter {
        private final int limit;
        private final BudgetPeriod period;
        private final AtomicInteger used = new AtomicInteger(0);
        private final AtomicLong windowStart;

        BudgetCounter(int limit, BudgetPeriod period, long now) {
            this.limit = limit;
            this.period = period;
            this.windowStart = new AtomicLong(now);
        }

        /** 严格预扣（绝不超卖）。 */
        synchronized boolean tryAcquire(int tokens, long now) {
            resetIfExpired(now);
            if (used.get() + tokens > limit) {
                return false;
            }
            used.addAndGet(tokens);
            return true;
        }

        /** 退还预算（对账退差）。 */
        synchronized void refund(int tokens, long now) {
            resetIfExpired(now);
            used.updateAndGet(v -> Math.max(0, v - tokens));
        }

        /** 强制追加预算（对账补差，真实 token 已消费，可能略微超限 —— 计费准确性优先）。 */
        synchronized void forceAdd(int tokens, long now) {
            resetIfExpired(now);
            int after = used.addAndGet(tokens);
            if (after > limit) {
                log.warn("[BudgetCounter] 预算超限补扣 used={} limit={} period={}（成本已实际发生，强制入账）",
                        after, limit, period);
            }
        }

        /** 只读预检（不扣减）。 */
        synchronized boolean peek(int tokens, long now) {
            resetIfExpired(now);
            return used.get() + tokens <= limit;
        }

        /** 预算周期。 */
        BudgetPeriod period() {
            return period;
        }

        @Override
        public synchronized void resetIfExpired(long now) {
            if (now - windowStart.get() >= period.durationMillis()) {
                used.set(0);
                windowStart.set(now);
            }
        }

        @Override
        public synchronized int currentUsage(long now) {
            resetIfExpired(now);
            return used.get();
        }

        @Override
        public synchronized int remaining(long now) {
            resetIfExpired(now);
            return Math.max(0, limit - used.get());
        }

        @Override
        public int limit() {
            return limit;
        }

        @Override
        public long windowStartMillis() {
            return windowStart.get();
        }
    }

    /**
     * 预算周期：按天 / 按月重置。
     */
    private enum BudgetPeriod {
        /** 每日重置。 */
        DAILY(24L * 60 * 60 * 1000),
        /** 每月重置（按 30 天近似）。 */
        MONTHLY(30L * 24 * 60 * 60 * 1000);

        private final long durationMillis;

        BudgetPeriod(long durationMillis) {
            this.durationMillis = durationMillis;
        }

        long durationMillis() {
            return durationMillis;
        }
    }
}
