package com.contentops.common.observability;

import com.contentops.common.cost.WorkflowCostGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LLM 可观测性服务：记录调用 trace、聚合 token/成本/延迟统计、按保留期清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmTraceService {

    private final LlmTraceRepository repository;
    private final ObservabilityProperties properties;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    /**
     * 记录一次 LLM 调用（由 GuardedChatModel 在成功/失败时调用）。
     */
    public void record(
            String model,
            Long tokensIn,
            Long tokensOut,
            Integer promptChars,
            Integer outputChars,
            long latencyMs,
            String status,
            String errorMessage) {
        if (!properties.isEnabled()) {
            return;
        }
        String stage = LlmTraceContext.stage() == null ? "unknown" : LlmTraceContext.stage();
        String agent = LlmTraceContext.agent() == null ? "unknown" : LlmTraceContext.agent();
        String modelName = model == null ? "unknown" : model;
        recordMetrics(stage, agent, modelName, tokensIn, tokensOut, latencyMs, status, errorMessage);
        String otelTraceId = null;
        String otelSpanId = null;
        try {
            Span current = tracer.currentSpan();
            if (current != null) {
                otelTraceId = current.context().traceId();
                otelSpanId = current.context().spanId();
            }
        } catch (Exception ignored) {
            // 无追踪上下文时忽略
        }
        repository.insert(LlmTrace.builder()
                .traceId(UUID.randomUUID().toString())
                .workflowId(WorkflowCostGuard.currentWorkflowId())
                .stage(stage)
                .agent(agent)
                .model(modelName)
                .tokensIn(tokensIn)
                .tokensOut(tokensOut)
                .promptChars(promptChars)
                .outputChars(outputChars)
                .latencyMs(latencyMs)
                .status(status)
                .errorMessage(errorMessage)
                .createdAt(LocalDateTime.now())
                .otelTraceId(otelTraceId)
                .otelSpanId(otelSpanId)
                .build());
    }

    /**
     * Micrometer 指标：Prometheus 抓取 LLM 调用/token/延迟/成本/错误。
     * 命名遵循 Prometheus 约定：计数器带 _total，延迟用 Timer（可算分位数）。
     */
    private void recordMetrics(String stage, String agent, String model,
                               Long tokensIn, Long tokensOut, long latencyMs,
                               String status, String errorMessage) {
        try {
            String[] tags = {"stage", stage, "agent", agent, "model", model};
            meterRegistry.counter("contentops_llm_calls_total", tags).increment();
            if (tokensIn != null) {
                meterRegistry.counter("contentops_llm_tokens_in_total", tags)
                        .increment(tokensIn);
            }
            if (tokensOut != null) {
                meterRegistry.counter("contentops_llm_tokens_out_total", tags)
                        .increment(tokensOut);
            }
            Timer.builder("contentops_llm_latency_seconds")
                    .tags(tags)
                    .publishPercentileHistogram(true)
                    .register(meterRegistry)
                    .record(java.time.Duration.ofMillis(latencyMs));
            if ("error".equals(status)) {
                meterRegistry.counter("contentops_llm_errors_total", tags).increment();
            }
            // 估算成本（美元）按模型累计
            ObservabilityProperties.Pricing pricing = properties.getPricing().get(model);
            double inPrice = pricing == null ? 0.27 : pricing.getInputPerMillion();
            double outPrice = pricing == null ? 1.10 : pricing.getOutputPerMillion();
            long in = tokensIn == null ? 0 : tokensIn;
            long out = tokensOut == null ? 0 : tokensOut;
            double cost = in / 1_000_000.0 * inPrice + out / 1_000_000.0 * outPrice;
            meterRegistry.counter("contentops_llm_cost_usd_total", "model", model)
                    .increment(cost);
        } catch (Exception e) {
            log.debug("[Observability] Micrometer 指标记录失败: {}", e.getMessage());
        }
    }

    /** 最近 trace（可按 stage/agent/workflow 过滤） */
    public List<LlmTrace> traces(String stage, String agent, String workflowId, Integer limit) {
        int safeLimit = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repository.findRecent(stage, agent, workflowId, safeLimit);
    }

    /** 汇总统计：调用数 / token / 平均延迟 / 错误数 / 估算成本 + 按阶段/Agent 排行 + 小时级时序 */
    public Map<String, Object> stats(Integer hours) {
        int h = hours == null || hours <= 0 ? 24 : Math.min(hours, 168);
        java.sql.Timestamp since = java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(h));
        List<Map<String, Object>> rows = repository.stats(since);
        long calls = 0, tokensIn = 0, tokensOut = 0, errors = 0;
        double latencySum = 0;
        for (Map<String, Object> row : rows) {
            calls += ((Number) row.get("calls")).longValue();
            tokensIn += ((Number) row.get("tokens_in")).longValue();
            tokensOut += ((Number) row.get("tokens_out")).longValue();
            errors += ((Number) row.get("errors")).longValue();
            latencySum += ((Number) row.get("avg_latency_ms")).doubleValue()
                    * ((Number) row.get("calls")).longValue();
        }
        double avgLatency = calls == 0 ? 0 : latencySum / calls;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hours", h);
        data.put("calls", calls);
        data.put("tokensIn", tokensIn);
        data.put("tokensOut", tokensOut);
        data.put("avgLatencyMs", Math.round(avgLatency * 10.0) / 10.0);
        data.put("errors", errors);
        data.put("errorRate", calls == 0 ? 0 : Math.round(errors * 1000.0 / calls) / 10.0);
        data.put("estimatedCostUsd", estimateCost(since));
        data.put("byStageAgent", rows);
        data.put("timeseries", repository.timeseries(since));
        return data;
    }

    private double estimateCost(java.sql.Timestamp since) {
        double cost = 0;
        for (Map<String, Object> row : repository.modelCost(since)) {
            String model = String.valueOf(row.get("model"));
            long in = ((Number) row.get("tokens_in")).longValue();
            long out = ((Number) row.get("tokens_out")).longValue();
            ObservabilityProperties.Pricing pricing = properties.getPricing().get(model);
            double inPrice = pricing == null ? 0.27 : pricing.getInputPerMillion();
            double outPrice = pricing == null ? 1.10 : pricing.getOutputPerMillion();
            cost += in / 1_000_000.0 * inPrice + out / 1_000_000.0 * outPrice;
        }
        return BigDecimal.valueOf(cost).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /** 每日清理超期 trace */
    @Scheduled(cron = "${contentops.observability.llm.cleanup-cron:0 30 3 * * *}")
    public void cleanupOldTraces() {
        if (!properties.isEnabled()) {
            return;
        }
        int days = Math.max(1, properties.getRetentionDays());
        java.sql.Timestamp cutoff = java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(days));
        int deleted = repository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("[Observability] 清理过期 LLM trace: deleted={}", deleted);
        }
    }
}
