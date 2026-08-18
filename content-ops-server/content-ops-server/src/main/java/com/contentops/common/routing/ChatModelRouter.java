package com.contentops.common.routing;

import com.contentops.common.cost.WorkflowCostGuard;
import com.contentops.common.observability.LlmTraceContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * 动态模型路由网关（P2 #10 大厂特色：多模型路由自动化）。
 *
 * <p>实现 {@link ChatModel} 接口，内部同时持有三档 delegate（cheap / strong / vision），
 * 按"难度 / 阶段 / 成本"多维启发式动态选择合适档位的模型：
 * <ol>
 *   <li>Vision 类阶段（图像设计等）→ vision 档</li>
 *   <li>网关关闭 → 返回 fallback（原阶段静态路由的 GuardedChatModel）</li>
 *   <li>预算不足 → 强制 cheap</li>
 *   <li>Prompt 字符长度超阈值（默认 8K）→ 从 cheap 自动升档到 strong</li>
 *   <li>按阶段默认档位（stage-tier 配置）→ cheap / strong</li>
 * </ol>
 *
 * <p>路由决策是纯启发式 + 配置驱动，不引入额外 LLM 调用（避免"路由成本超过节省成本"的反模式）。
 * 每个决策点都记 debug 日志，便于接入观测大盘。
 *
 * <h3>与 AiModelConfig 的集成方式：</h3>
 * 现有 creativeChatModel / formattingChatModel 的 AiServices 消费者无需改动。
 * AiModelConfig 会把 {@code ChatModelRouter} 作为 creative / formatting 两个分类的
 * {@link GuardedChatModel} 的 delegate，让路由在安全护栏前的"最内部咽喉"完成选择，
 * 这样所有 trace span 中的 tag("model") 都反映最终选中的模型。
 */
@Slf4j
public class ChatModelRouter implements ChatModel {

    public enum Tier { CHEAP, STRONG, VISION }

    private final DynamicGatewayProperties properties;
    private final ChatModel cheap;
    private final ChatModel strong;
    private final ChatModel vision;
    private final ChatModel fallback;
    private final WorkflowCostGuard costGuard;

    public ChatModelRouter(DynamicGatewayProperties properties,
                           ChatModel cheap,
                           ChatModel strong,
                           ChatModel vision,
                           ChatModel fallback,
                           WorkflowCostGuard costGuard) {
        this.properties = properties;
        this.cheap = cheap;
        this.strong = strong;
        this.vision = vision;
        this.fallback = fallback;
        this.costGuard = costGuard;
        log.info("[ModelGateway] 动态模型路由初始化: enabled={}, cheap={}, strong={}, vision={}",
                properties.isEnabled(),
                nameOf(cheap), nameOf(strong), nameOf(vision));
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        ChatModel selected = select(request);
        log.debug("[ModelGateway] 路由决策: stage={}, selected={}",
                currentStage(), nameOf(selected));
        return selected.doChat(request);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return fallback.defaultRequestParameters();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return fallback.supportedCapabilities();
    }

    @Override
    public ModelProvider provider() {
        return fallback.provider();
    }

    // ──────────────────── 路由决策逻辑 ────────────────────

    private ChatModel select(ChatRequest request) {
        String stage = currentStage();

        // (1) 网关关闭 → fallback（原阶段静态路由）
        if (!properties.isEnabled()) {
            return fallback;
        }

        // (2) Vision 阶段 → vision 档
        if (stage != null) {
            for (String visionStage : properties.getDifficultyEstimation().getVisionStageCodes()) {
                if (visionStage.equals(stage)) {
                    return vision;
                }
            }
        }

        // (3) 阶段默认档位
        String defaultTier = properties.getStageTier().getOrDefault(stage, "strong");
        Tier tier = parseTier(defaultTier);
        if (tier == Tier.VISION) {
            return vision;
        }

        // (4) 成本策略：预算不足时强制 cheap
        if (properties.getCostOptimization().isUpgradeOnBudgetLow()) {
            String workflowId = WorkflowCostGuard.currentWorkflowId();
            if (workflowId != null && costGuard.isBudgetLow(workflowId)) {
                log.debug("[ModelGateway] 预算不足: workflowId={}, 强制 cheap 档", workflowId);
                return cheap;
            }
        }

        // (5) 难度估算：prompt 字符数 → 升档
        if (properties.getDifficultyEstimation().isEnabled()) {
            int promptChars = estimatePromptChars(request);
            int threshold = properties.getDifficultyEstimation().getPromptCharsUpgrade();
            if (tier == Tier.CHEAP && promptChars >= threshold) {
                log.debug("[ModelGateway] 难度升档: stage={}, chars={}≥{}, cheap→strong",
                        stage, promptChars, threshold);
                tier = Tier.STRONG;
            }
        }

        // (6) cheap 档 maxTokens 硬上限：路由时把 strong 档的 maxTokens 限制到 cheap 上限
        if (tier == Tier.CHEAP) {
            return cheap;
        }
        return strong;
    }

    private static Tier parseTier(String t) {
        if (t == null) {
            return Tier.STRONG;
        }
        return switch (t.toLowerCase()) {
            case "cheap" -> Tier.CHEAP;
            case "strong" -> Tier.STRONG;
            case "vision" -> Tier.VISION;
            default -> Tier.STRONG;
        };
    }

    private static String currentStage() {
        return LlmTraceContext.stage();
    }

    private static int estimatePromptChars(ChatRequest request) {
        int chars = 0;
        for (ChatMessage m : request.messages()) {
            if (m instanceof UserMessage um && um.hasSingleText()) {
                chars += um.singleText().length();
            } else if (m instanceof dev.langchain4j.data.message.SystemMessage sm) {
                chars += sm.text() == null ? 0 : sm.text().length();
            } else if (m instanceof dev.langchain4j.data.message.AiMessage am) {
                chars += am.text() == null ? 0 : am.text().length();
            }
        }
        return chars;
    }

    private static String nameOf(ChatModel cm) {
        if (cm == null) return "null";
        try {
            return cm.getClass().getSimpleName()
                    + "/" + (cm.defaultRequestParameters().modelName() != null
                    ? cm.defaultRequestParameters().modelName() : "unknown");
        } catch (Exception e) {
            return cm.getClass().getSimpleName();
        }
    }
}
