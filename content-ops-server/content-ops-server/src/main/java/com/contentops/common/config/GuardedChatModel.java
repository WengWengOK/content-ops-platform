package com.contentops.common.config;

import com.contentops.common.safety.SafetyGuardService;
import com.contentops.common.safety.SafetyGuardService.SafetyResult;
import com.contentops.common.cost.WorkflowCostGuard;
import com.contentops.common.observability.LlmTraceService;
import com.contentops.common.observability.LlmTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 安全护栏 ChatModel 装饰器 — 把 {@link SafetyGuardService} 接入真实 LLM 调用链。
 *
 * <p>所有 Agent（以及 RAG 查询重写、视觉/音频等多模态服务）最终都通过
 * {@link ChatModel} 调用 LLM。此前安全框架只存在于代码中、从未被调用；
 * 通过本装饰器在单一咽喉点执行双层防护：
 * <ul>
 *   <li><b>输入防护</b>：调用 LLM 前对每条用户消息执行 {@code inputGuard}
 *       （Prompt 注入检测 + 内容安全过滤），命中时用净化后内容替换。</li>
 *   <li><b>输出防护</b>：LLM 返回后对 AI 消息文本执行 {@code outputGuard}
 *       （敏感泄露 / 有害建议 / 版权 / 幻觉 / PII 脱敏），命中时用净化内容替换；
 *       净化后为空则替换为安全占位文本，保证流程不中断。</li>
 * </ul>
 *
 * <p>装饰器只修改被判定为需要净化的消息，其余消息原样透传，避免引入额外开销。
 * 系统提示词与工具调用结果不参与输入防护（属于受信内容，且可避免误伤项目自身 Prompt）。
 */
@Slf4j
public class GuardedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final SafetyGuardService safetyGuard;
    private final WorkflowCostGuard costGuard;
    private final LlmTraceService llmTraceService;
    private final String modelName;
    private final Tracer tracer;

    public GuardedChatModel(ChatModel delegate, SafetyGuardService safetyGuard,
                            WorkflowCostGuard costGuard, LlmTraceService llmTraceService,
                            String modelName, Tracer tracer) {
        this.delegate = delegate;
        this.safetyGuard = safetyGuard;
        this.costGuard = costGuard;
        this.llmTraceService = llmTraceService;
        this.modelName = modelName;
        this.tracer = tracer;
        log.info("[SafetyGuard] GuardedChatModel 已装配: delegate={}", delegate.getClass().getSimpleName());
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String workflowId = WorkflowCostGuard.currentWorkflowId();
        // 0. 成本预算 / 熔断预检：预算用尽或熔断打开时不发起真实调用
        costGuard.checkBlocked(workflowId);

        // 1. 输入防护（仅用户消息）
        List<ChatMessage> guardedMessages = guardInputs(request.messages());
        ChatRequest guardedRequest = (guardedMessages == request.messages())
                ? request
                : ChatRequest.builder()
                        .messages(guardedMessages)
                        .parameters(request.parameters())
                        .build();

        // 2. 委托真实模型（OTel span + 失败记账，供熔断器决策）
        Span span = tracer.nextSpan().name("llm.call")
                .tag("model", modelName)
                .tag("stage", LlmTraceContext.stage() == null ? "unknown" : LlmTraceContext.stage())
                .tag("agent", LlmTraceContext.agent() == null ? "unknown" : LlmTraceContext.agent())
                .start();
        ChatResponse response;
        long startNanos = System.nanoTime();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            response = delegate.doChat(guardedRequest);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (response != null && response.metadata() != null && response.metadata().tokenUsage() != null) {
                var usage = response.metadata().tokenUsage();
                if (usage.inputTokenCount() != null) {
                    span.tag("tokensIn", String.valueOf(usage.inputTokenCount()));
                }
                if (usage.outputTokenCount() != null) {
                    span.tag("tokensOut", String.valueOf(usage.outputTokenCount()));
                }
                costGuard.recordUsage(workflowId, response.metadata().tokenUsage());
            }
            span.tag("status", "success");
            recordTrace(guardedRequest, response, startNanos, "success", null);
            span.end();
        } catch (RuntimeException e) {
            span.tag("status", "error");
            span.error(e);
            recordTrace(guardedRequest, null, startNanos, "error", e.getMessage());
            span.end();
            costGuard.recordFailure(e);
            throw e;
        }

        // 3. 输出防护
        return guardOutput(response);
    }

    /**
     * LLM 可观测性埋点：记录 token / 延迟 / 状态（成本大盘与故障排查数据源）。
     */
    private void recordTrace(ChatRequest request, ChatResponse response,
                             long startNanos, String status, String errorMessage) {
        try {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            Long tokensIn = null;
            Long tokensOut = null;
            Integer outputChars = null;
            if (response != null && response.metadata() != null
                    && response.metadata().tokenUsage() != null) {
                var usage = response.metadata().tokenUsage();
                tokensIn = usage.inputTokenCount() == null ? null : usage.inputTokenCount().longValue();
                tokensOut = usage.outputTokenCount() == null ? null : usage.outputTokenCount().longValue();
            }
            String outputText = response == null || response.aiMessage() == null
                    ? null : response.aiMessage().text();
            if (outputText != null) {
                outputChars = outputText.length();
            }
            int promptChars = 0;
            for (dev.langchain4j.data.message.ChatMessage message : request.messages()) {
                if (message instanceof dev.langchain4j.data.message.UserMessage userMessage
                        && userMessage.hasSingleText()) {
                    promptChars += userMessage.singleText().length();
                } else if (message instanceof dev.langchain4j.data.message.SystemMessage systemMessage) {
                    promptChars += systemMessage.text() == null ? 0 : systemMessage.text().length();
                }
            }
            llmTraceService.record(
                    modelName,
                    tokensIn,
                    tokensOut,
                    promptChars,
                    outputChars,
                    latencyMs,
                    status,
                    errorMessage);
        } catch (Exception e) {
            log.debug("[Observability] trace 记录失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 对用户消息执行输入防护；无命中时原样返回同一列表。
     */
    private List<ChatMessage> guardInputs(List<ChatMessage> messages) {
        List<ChatMessage> result = null;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.type() != ChatMessageType.USER || !(message instanceof UserMessage userMessage)) {
                continue;
            }
            // 多模态/多片段消息不做文本级净化，保持原样透传
            if (!userMessage.hasSingleText()) {
                continue;
            }
            String original = userMessage.singleText();
            SafetyResult guardResult = safetyGuard.inputGuard(original);
            String sanitized = guardResult.sanitizedContent();
            log.debug("[SafetyGuard] 输入检查: message#{} len={}, risk={}, violations={}, changed={}",
                    i, original.length(), guardResult.riskLevel(), guardResult.violations(),
                    !sanitized.equals(original));
            if (sanitized.equals(original)) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(messages);
            }
            result.set(i, new UserMessage(sanitized));
            log.warn("[SafetyGuard] 输入已净化: message#{} {}→{} 字符, risk={}, violations={}",
                    i, original.length(), sanitized.length(), guardResult.riskLevel(), guardResult.violations());
        }
        return result != null ? result : messages;
    }

    /**
     * 对 AI 消息执行输出防护；无命中时原样返回。
     */
    private ChatResponse guardOutput(ChatResponse response) {
        AiMessage aiMessage = response.aiMessage();
        if (aiMessage == null || aiMessage.text() == null) {
            return response;
        }

        // 结构化 JSON 输出（AiServices POJO 解析）不做文本级替换，避免破坏机器可读载荷
        String trimmed = aiMessage.text().strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")
                || trimmed.startsWith("```json") || trimmed.startsWith("```")) {
            SafetyResult jsonGuard = safetyGuard.outputGuard(aiMessage.text());
            if (jsonGuard.hasViolations() || !jsonGuard.sanitizedContent().equals(aiMessage.text())) {
                log.debug("[SafetyGuard] 结构化输出仅记录检测结果，不替换文本: risk={}, violations={}",
                        jsonGuard.riskLevel(), jsonGuard.violations());
            }
            return response;
        }

        SafetyResult guardResult = safetyGuard.outputGuard(aiMessage.text());
        String sanitized = guardResult.sanitizedContent();
        if (sanitized.equals(aiMessage.text())) {
            return response;
        }
        String finalText = sanitized.isBlank() ? "[输出已被安全策略过滤]" : sanitized;
        log.warn("[SafetyGuard] 输出已净化: {}→{} 字符, risk={}, violations={}",
                aiMessage.text().length(), finalText.length(), guardResult.riskLevel(), guardResult.violations());
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(finalText))
                .id(response.id())
                .modelName(response.modelName())
                .tokenUsage(response.tokenUsage())
                .finishReason(response.finishReason())
                .build();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }
}
