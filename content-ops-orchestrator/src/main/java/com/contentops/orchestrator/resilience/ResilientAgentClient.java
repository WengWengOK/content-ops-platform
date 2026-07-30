package com.contentops.orchestrator.resilience;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.metrics.TokenMetricsService;
import com.contentops.orchestrator.service.AgentFeignClients.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 弹性 Agent 调用包装器（P1: 弹性与可观测性）。
 *
 * <p>使用 Resilience4j 的 {@code @CircuitBreaker} + {@code @Retry} 注解包装所有 Feign 调用：
 * <ul>
 *   <li><b>熔断</b>：连续失败超过阈值时自动熔断，直接返回降级响应，避免雪崩</li>
 *   <li><b>重试</b>：LLM API 偶发超时时自动重试（最多 3 次）</li>
 *   <li><b>降级</b>：熔断或重试耗尽后返回轻量降级响应（提示用户稍后重试）</li>
 *   <li><b>指标</b>：每次调用记录 Micrometer 指标（成功率、延迟）</li>
 * </ul>
 *
 * <p><b>降级策略</b>：当前返回明确的降级提示信息，不尝试切换到轻量模型
 * （切换轻量模型需要多模型路由能力，属于 P2 范围）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResilientAgentClient {

    private final TopicAgentClient topicAgentClient;
    private final ContentAgentClient contentAgentClient;
    private final ImageAgentClient imageAgentClient;
    private final PublishAgentClient publishAgentClient;
    private final AnalysisAgentClient analysisAgentClient;
    private final OptimizeAgentClient optimizeAgentClient;
    private final TokenMetricsService metricsService;

    // ══════════════════ 同步调用（带熔断+重试） ══════════════════

    /**
     * Topic Agent 调用（带熔断 + 重试）。
     */
    @CircuitBreaker(name = "topicAgent", fallbackMethod = "topicFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callTopic(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = topicAgentClient.execute(request);
            metricsService.recordAgentCall("topic-planning", response.isSuccess());
            metricsService.recordAgentDuration("topic-planning",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("topic-planning", false);
            throw e;
        }
    }

    /**
     * Content Agent 一次性调用（带熔断 + 重试，兼容非渐进式路径）。
     */
    @CircuitBreaker(name = "contentAgent", fallbackMethod = "contentFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callContentExecute(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = contentAgentClient.execute(request);
            metricsService.recordAgentCall("content-creation", response.isSuccess());
            metricsService.recordAgentDuration("content-creation",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("content-creation", false);
            throw e;
        }
    }

    /**
     * Image Agent 一次性调用（带熔断 + 重试，兼容非渐进式路径）。
     */
    @CircuitBreaker(name = "imageAgent", fallbackMethod = "imageFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callImageExecute(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = imageAgentClient.execute(request);
            metricsService.recordAgentCall("image-design", response.isSuccess());
            metricsService.recordAgentDuration("image-design",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("image-design", false);
            throw e;
        }
    }

    /**
     * Publish Agent 调用（带熔断 + 重试）。
     */
    @CircuitBreaker(name = "publishAgent", fallbackMethod = "publishFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callPublish(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = publishAgentClient.execute(request);
            metricsService.recordAgentCall("publishing", response.isSuccess());
            metricsService.recordAgentDuration("publishing",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("publishing", false);
            throw e;
        }
    }

    /**
     * Analysis Agent 调用（带熔断 + 重试）。
     */
    @CircuitBreaker(name = "analysisAgent", fallbackMethod = "analysisFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callAnalysis(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = analysisAgentClient.execute(request);
            metricsService.recordAgentCall("data-analysis", response.isSuccess());
            metricsService.recordAgentDuration("data-analysis",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("data-analysis", false);
            throw e;
        }
    }

    /**
     * Optimize Agent 调用（带熔断 + 重试）。
     */
    @CircuitBreaker(name = "optimizeAgent", fallbackMethod = "optimizeFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callOptimize(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = optimizeAgentClient.execute(request);
            metricsService.recordAgentCall("optimization", response.isSuccess());
            metricsService.recordAgentDuration("optimization",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("optimization", false);
            throw e;
        }
    }

    /**
     * Content Agent 大纲生成（带熔断 + 重试，同步模式）。
     */
    @CircuitBreaker(name = "contentAgent", fallbackMethod = "contentFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callContentOutline(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = contentAgentClient.generateOutline(request);
            metricsService.recordAgentCall("content-creation", response.isSuccess());
            metricsService.recordAgentDuration("content-creation",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("content-creation", false);
            throw e;
        }
    }

    /**
     * Content Agent 初稿生成（带熔断 + 重试，同步模式）。
     */
    @CircuitBreaker(name = "contentAgent", fallbackMethod = "contentFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callContentDraft(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = contentAgentClient.generateDraft(request);
            metricsService.recordAgentCall("content-creation", response.isSuccess());
            metricsService.recordAgentDuration("content-creation",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("content-creation", false);
            throw e;
        }
    }

    /**
     * Image Agent 风格方向（带熔断 + 重试，同步模式）。
     */
    @CircuitBreaker(name = "imageAgent", fallbackMethod = "imageFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callImageStyles(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = imageAgentClient.generateStyleDirections(request);
            metricsService.recordAgentCall("image-design", response.isSuccess());
            metricsService.recordAgentDuration("image-design",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("image-design", false);
            throw e;
        }
    }

    /**
     * Image Agent 批量生图（带熔断 + 重试，同步模式）。
     */
    @CircuitBreaker(name = "imageAgent", fallbackMethod = "imageFallback")
    @Retry(name = "agentRetry")
    public AgentResponse<Map<String, Object>> callImageGenerate(AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentResponse<Map<String, Object>> response = imageAgentClient.generateImages(request);
            metricsService.recordAgentCall("image-design", response.isSuccess());
            metricsService.recordAgentDuration("image-design",
                    Duration.ofMillis(System.currentTimeMillis() - start));
            return response;
        } catch (Exception e) {
            metricsService.recordAgentCall("image-design", false);
            throw e;
        }
    }

    // ══════════════════ 降级方法 ══════════════════

    /**
     * Topic Agent 降级响应。
     *
     * <p>熔断或重试耗尽时返回明确的降级提示，不尝试切换到轻量模型
     * （多模型路由属于 P2 范围）。
     */
    @SuppressWarnings("unused")
    private AgentResponse<Map<String, Object>> topicFallback(AgentTaskRequest request, Exception e) {
        log.warn("[CircuitBreaker] TopicAgent 降级触发: workflowId={}, error={}",
                request.getWorkflowId(), e.getMessage());
        return AgentResponse.failure("topic-planning",
                "选题策划服务暂时不可用（已触发熔断降级），请稍后重试或使用讨论模式手动选题。");
    }

    @SuppressWarnings("unused")
    private AgentResponse<Map<String, Object>> contentFallback(AgentTaskRequest request, Exception e) {
        log.warn("[CircuitBreaker] ContentAgent 降级触发: workflowId={}, error={}",
                request.getWorkflowId(), e.getMessage());
        return AgentResponse.failure("content-creation",
                "内容创作服务暂时不可用（已触发熔断降级），请稍后重试。");
    }

    @SuppressWarnings("unused")
    private AgentResponse<Map<String, Object>> imageFallback(AgentTaskRequest request, Exception e) {
        log.warn("[CircuitBreaker] ImageAgent 降级触发: workflowId={}, error={}",
                request.getWorkflowId(), e.getMessage());
        return AgentResponse.failure("image-design",
                "配图设计服务暂时不可用（已触发熔断降级），请稍后重试。");
    }

    @SuppressWarnings("unused")
    private AgentResponse<Map<String, Object>> publishFallback(AgentTaskRequest request, Exception e) {
        log.warn("[CircuitBreaker] PublishAgent 降级触发: workflowId={}, error={}",
                request.getWorkflowId(), e.getMessage());
        return AgentResponse.failure("publishing",
                "排版发布服务暂时不可用（已触发熔断降级），请稍后重试。");
    }

    @SuppressWarnings("unused")
    private AgentResponse<Map<String, Object>> analysisFallback(AgentTaskRequest request, Exception e) {
        log.warn("[CircuitBreaker] AnalysisAgent 降级触发: workflowId={}, error={}",
                request.getWorkflowId(), e.getMessage());
        return AgentResponse.failure("data-analysis",
                "数据分析服务暂时不可用（已触发熔断降级），请稍后重试。");
    }

    @SuppressWarnings("unused")
    private AgentResponse<Map<String, Object>> optimizeFallback(AgentTaskRequest request, Exception e) {
        log.warn("[CircuitBreaker] OptimizeAgent 降级触发: workflowId={}, error={}",
                request.getWorkflowId(), e.getMessage());
        return AgentResponse.failure("optimization",
                "优化迭代服务暂时不可用（已触发熔断降级），请稍后重试。");
    }
}
