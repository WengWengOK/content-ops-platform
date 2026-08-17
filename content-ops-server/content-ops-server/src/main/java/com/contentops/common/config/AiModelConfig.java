package com.contentops.common.config;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.cost.WorkflowCostGuard;
import com.contentops.common.routing.ModelConfig;
import com.contentops.common.routing.ModelRoutingProperties;
import com.contentops.common.routing.ModelRoutingService;
import com.contentops.common.safety.SafetyGuardService;
import com.contentops.common.observability.LlmTraceService;
import io.micrometer.tracing.Tracer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AI 模型装配 — 把 {@link ModelRoutingService} 真正接入 Agent 调用链。
 *
 * <p>此前各 Agent 配置直接注入 LangChain4j 自动配置的单一 ChatModel Bean，
 * 路由服务（创意类 gpt-4o/0.8 vs 格式化类 gpt-4o-mini/0.3）从未被使用。
 * 本配置按阶段分类创建两个模型 Bean，并统一包裹 {@link GuardedChatModel}
 * 让 {@link SafetyGuardService} 在真实 LLM 调用链上生效：
 * <ul>
 *   <li>{@code creativeChatModel}（@Primary）：选题 / 内容 / 配图等创意类阶段</li>
 *   <li>{@code formattingChatModel}：发布 / 分析 / 优化等格式化类阶段</li>
 * </ul>
 *
 * <p>模型名称与温度由 {@link ModelRoutingService#getModelConfig(AgentStage)}
 * 决定，支持 application.yml 中 {@code contentops.model-routing.*} 按阶段覆盖。
 */
@Slf4j
@Configuration
public class AiModelConfig {

    private ChatModel buildGuarded(ModelRoutingService routing,
                                   ModelRoutingProperties properties,
                                   AgentStage stage,
                                   SafetyGuardService safetyGuard,
                                   WorkflowCostGuard costGuard,
                                   LlmTraceService llmTraceService,
                                   Tracer tracer,
                                   String tag) {
        ModelConfig config = routing.getModelConfig(stage);
        OpenAiChatModel raw = OpenAiChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .build();
        log.info("[ModelRouting] {} ChatModel 构建完成: model={}, temperature={}, maxTokens={}",
                tag, config.getModelName(), config.getTemperature(), config.getMaxTokens());
        return new GuardedChatModel(raw, safetyGuard, costGuard, llmTraceService,
                config.getModelName(), tracer);
    }

    /**
     * 创意类模型（选题 / 内容 / 配图）— 高温度大模型；@Primary 供
     * ObjectProvider 等通用消费者解析（RAG 重写、视觉/音频等）。
     */
    @Bean
    @Primary
    public ChatModel creativeChatModel(ModelRoutingService routing,
                                       ModelRoutingProperties properties,
                                       SafetyGuardService safetyGuard,
                                       WorkflowCostGuard costGuard,
                                       LlmTraceService llmTraceService,
                                       Tracer tracer) {
        return buildGuarded(routing, properties, AgentStage.TOPIC_PLANNING, safetyGuard, costGuard,
                llmTraceService, tracer, "创意类");
    }

    /**
     * 格式化类模型（发布 / 分析 / 优化）— 低温度小模型，保证确定性。
     */
    @Bean
    public ChatModel formattingChatModel(ModelRoutingService routing,
                                         ModelRoutingProperties properties,
                                         SafetyGuardService safetyGuard,
                                         WorkflowCostGuard costGuard,
                                         LlmTraceService llmTraceService,
                                         Tracer tracer) {
        return buildGuarded(routing, properties, AgentStage.PUBLISHING, safetyGuard, costGuard,
                llmTraceService, tracer, "格式化类");
    }

    /**
     * 流式模型（讨论对话 SSE 流式输出用）：与创意类同配置，走 StreamingChatModel。
     */
    @Bean
    public StreamingChatModel streamingChatModel(ModelRoutingService routing,
                                                  ModelRoutingProperties properties) {
        ModelConfig config = routing.getModelConfig(AgentStage.TOPIC_PLANNING);
        OpenAiStreamingChatModel streaming = OpenAiStreamingChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .build();
        log.info("[ModelRouting] StreamingChatModel 构建完成: model={}", config.getModelName());
        return streaming;
    }
}
