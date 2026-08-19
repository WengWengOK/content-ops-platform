package com.contentops.common.config;

import com.contentops.common.cost.WorkflowCostGuard;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.routing.ChatModelRouter;
import com.contentops.common.routing.DynamicGatewayProperties;
import com.contentops.common.routing.ModelConfig;
import com.contentops.common.routing.ModelRoutingProperties;
import com.contentops.common.routing.ModelRoutingService;
import com.contentops.common.safety.SafetyGuardService;
import com.contentops.common.observability.LlmTraceService;
import com.contentops.common.event.WorkflowEventBroadcaster;
import io.micrometer.tracing.Tracer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AI 模型装配 — 把 {@link ModelRoutingService} + 动态路由网关 {@link ChatModelRouter}
 * 真正接入 Agent 调用链。
 *
 * <h3>三层结构（从外到内）</h3>
 * <ol>
 *   <li><b>最外层：{@link GuardedChatModel}</b> — 安全护栏（输入/输出净化）、
 *       OTel trace span、成本预算/熔断。面向所有 Agent 的 AiServices 注入。</li>
 *   <li><b>中间层：{@link ChatModelRouter}</b> — P2 #10 大厂特色：多模型路由自动化。
 *       按 stage / 难度估算 / 成本预算多维启发式选择 cheap/strong/vision 档。
 *       关闭时直接回退到原阶段静态路由模型（兼容性保证）。</li>
 *   <li><b>最内层：Raw OpenAiChatModel</b> — 三档模型（cheap / strong / vision）
 *       + 两档阶段静态路由（creative / formatting），共 5 个 raw 实例，
 *       由 Router 选择 delegate。</li>
 * </ol>
 *
 * <h3>两档面向 AiServices 注入（保持 Agent 零改动）</h3>
 * <ul>
 *   <li>{@code creativeChatModel}（@Primary）：选题 / 内容 / 配图 / 通用消费者；
 *       Router 的 stage-tier 默认走 strong 档。</li>
 *   <li>{@code formattingChatModel}：发布 / 分析 / 优化；
 *       Router 的 stage-tier 默认走 cheap 档。</li>
 * </ul>
 *
 * <p>模型名称与温度由 {@link ModelRoutingService#getModelConfig(AgentStage)} 决定，
 * 支持 application.yml 中 {@code contentops.model-routing.*} 按阶段覆盖。
 */
@Slf4j
@Configuration
public class AiModelConfig {

    private final WorkflowCostGuard workflowCostGuard;

    @Autowired
    public AiModelConfig(WorkflowCostGuard workflowCostGuard) {
        this.workflowCostGuard = workflowCostGuard;
    }

    /**
     * 构建 GuardedChatModel（最外层装饰器：安全护栏 + trace + 熔断 + 成本）。
     * 传入的 delegate 已经是 Router 或单模型 ChatModel。
     */
    private ChatModel buildGuarded(ChatModel delegate,
                                   String modelNameForTag,
                                   SafetyGuardService safetyGuard,
                                   LlmTraceService llmTraceService,
                                   Tracer tracer,
                                   WorkflowEventBroadcaster workflowEventBroadcaster) {
        return new GuardedChatModel(delegate, safetyGuard, workflowCostGuard, llmTraceService,
                modelNameForTag, tracer, workflowEventBroadcaster);
    }

    /**
     * 构建 Raw OpenAiChatModel（最内层）。
     */
    private ChatModel buildRaw(ModelRoutingProperties properties, ModelConfig config) {
        return OpenAiChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .build();
    }

    /**
     * 按指定模型名 + 全局默认 构建 Raw（三档 cheap/strong/vision 模型用）。
     */
    private ChatModel buildRawByName(ModelRoutingProperties properties,
                                     String modelName,
                                     Double temperature,
                                     Integer maxTokens) {
        ModelConfig cfg = new ModelConfig();
        cfg.setModelName(modelName);
        cfg.setTemperature(temperature != null ? temperature : properties.getDefaultTemperature());
        cfg.setMaxTokens(maxTokens != null ? maxTokens : properties.getDefaultMaxTokens());
        cfg.setProvider("openai");
        return buildRaw(properties, cfg);
    }

    /**
     * 动态路由网关（中间层）：持有三档模型，按 stage/难度/预算多维决策。
     *
     * <p>将此 Bean 作为 creativeChatModel / formattingChatModel 的内部 delegate，
     * 所有 Agent 的 GuardedChatModel 最终都流经 Router。
     */
    @Bean
    public ChatModelRouter chatModelRouter(ModelRoutingService routing,
                                           ModelRoutingProperties properties,
                                           DynamicGatewayProperties gateway) {
        // Cheap 档：默认小模型，maxTokens 强制不超过 cheapStageMaxTokens
        int cheapMax = Math.min(
                gateway.getCostOptimization().getCheapStageMaxTokens(),
                properties.getDefaultMaxTokens());
        ChatModel cheap = buildRawByName(properties,
                gateway.getTieredChatmodels().getCheap(), 0.3, cheapMax);
        // Strong 档：默认强模型，按 creative 分类的配置（default 大值）
        ModelConfig defaultStrong = routing.getModelConfig(AgentStage.TOPIC_PLANNING);
        ChatModel strong = buildRaw(properties, defaultStrong);
        // Vision 档：多模态
        ChatModel vision = buildRawByName(properties,
                gateway.getTieredChatmodels().getVision(), 0.8, properties.getDefaultMaxTokens());
        // Fallback：原阶段静态路由（TOPIC_PLANNING → formatting 默认，gateway 关闭时用）
        ModelConfig fallbackCfg = routing.getModelConfig(AgentStage.TOPIC_PLANNING);
        ChatModel fallback = buildRaw(properties, fallbackCfg);

        log.info("[ModelGateway] 三档 raw ChatModel 已构建: cheap={}, strong={}, vision={}",
                gateway.getTieredChatmodels().getCheap(),
                defaultStrong.getModelName(),
                gateway.getTieredChatmodels().getVision());
        return new ChatModelRouter(gateway, cheap, strong, vision, fallback, workflowCostGuard);
    }

    /**
     * 创意类模型（选题 / 内容 / 配图）— @Primary 供通用消费者解析。
     * 最外层 GuardedChatModel，delegate 是 ChatModelRouter（内部已按阶段路由）。
     */
    @Bean
    @Primary
    public ChatModel creativeChatModel(ChatModelRouter router,
                                       ModelRoutingService routing,
                                       ModelRoutingProperties properties,
                          SafetyGuardService safetyGuard,
                          LlmTraceService llmTraceService,
                          Tracer tracer,
                          WorkflowEventBroadcaster workflowEventBroadcaster) {
        // span 的 model 标签记录 creative 默认强模型名，便于观察（实际模型在 Router 内部实时选择）
        ModelConfig creativeCfg = routing.getModelConfig(AgentStage.TOPIC_PLANNING);
        return buildGuarded(router, creativeCfg.getModelName(), safetyGuard,
                llmTraceService, tracer, workflowEventBroadcaster);
    }

    /**
     * 格式化类模型（发布 / 分析 / 优化）— 低温度小模型，保证确定性。
     * 最外层 GuardedChatModel，delegate 是同一 ChatModelRouter（Router 内部按阶段默认档 cheap）。
     */
    @Bean
    public ChatModel formattingChatModel(ChatModelRouter router,
                                         ModelRoutingService routing,
                                         ModelRoutingProperties properties,
                            SafetyGuardService safetyGuard,
                            LlmTraceService llmTraceService,
                            Tracer tracer,
                            WorkflowEventBroadcaster workflowEventBroadcaster) {
        ModelConfig formattingCfg = routing.getModelConfig(AgentStage.PUBLISHING);
        return buildGuarded(router, formattingCfg.getModelName(), safetyGuard,
                llmTraceService, tracer, workflowEventBroadcaster);
    }

    /**
     * 流式模型（讨论对话 SSE 流式输出用）：独立 ChatModel，不走路由网关
     * （Router 的 ChatResponse.doChat 是非流式语义）。保持原实现。
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
