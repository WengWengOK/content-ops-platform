package com.contentops.topic.config;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.common.profile.competitor.CompetitorProfileService;
import com.contentops.common.profile.style.StyleEnricher;
import com.contentops.common.prompt.PromptFragmentService;
import com.contentops.common.prompt.PromptVersionService;
import com.contentops.topic.agent.TopicPlanningAgent;
import com.contentops.topic.tool.TopicResearchTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the {@link TopicPlanningAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 *
 * <p>The {@link ChatModel} bean is auto-configured by
 * {@code langchain4j-open-ai-spring-boot-starter} (see {@code langchain4j.open-ai.chat-model.*}
 * in {@code application.yml}). Here it is wired together with the
 * {@link TopicResearchTools} and {@link RedisChatMemoryProvider} so the agent
 * can recall previous conversation turns within the same workflow.
 *
 * <p><b>P1: 动态 Prompt 拼接</b>——当 {@code contentops.prompt.enabled=true} 时，
 * 通过 {@link PromptFragmentService#assembleTopicSystemMessage} 动态组装系统提示词，
 * 根据账号画像（领域、调性、平台）追加专属指导片段，并支持 A/B 测试变体。
 * 关闭时回退到 {@link TopicPlanningAgent} 上的 {@code @SystemMessage} 注解。
 *
 * <p>Memory isolation: the {@code @MemoryId} parameter on
 * {@link TopicPlanningAgent#planTopics} uses the format
 * {@code "topic-planning:{workflowId}"}, ensuring each workflow has its own
 * conversation history stored in Redis.
 */
@Slf4j
@Configuration
public class TopicAgentConfig {

    /** 竞品画像服务（可选注入，未配置时降级为空） */
    @Autowired(required = false)
    private CompetitorProfileService competitorProfileService;

    /** 风格注入器（可选注入，未配置时降级为空） */
    @Autowired(required = false)
    private StyleEnricher styleEnricher;

    @Bean
    public TopicPlanningAgent topicPlanningAgent(@Qualifier("creativeChatModel") ChatModel chatModel,
                                                  TopicResearchTools topicResearchTools,
                                                  RedisChatMemoryProvider chatMemoryProvider,
                                                  FileTools fileTools,
                                                  PromptFragmentService promptFragmentService,
                                                  PromptVersionService promptVersionService) {
        var builder = AiServices.builder(TopicPlanningAgent.class)
                .chatModel(chatModel)
                .tools(topicResearchTools, fileTools)
                .chatMemoryProvider(chatMemoryProvider);

        // P1: 动态 Prompt 拼接——启用时用 PromptFragmentService 组装，否则回退到 @SystemMessage
        // P0增强：动态 Prompt 拼接时，额外注入竞品画像和风格画像上下文
        if (promptVersionService.isDynamicPromptEnabled()) {
            builder.systemMessageProvider(variables -> {
                String basePrompt = promptFragmentService.assembleTopicSystemMessage(variables);
                // 注入风格画像（保持选题与历史高表现内容风格一致）
                if (styleEnricher != null) {
                    String accountId = extractAccountId(variables);
                    if (accountId != null && !accountId.isBlank()) {
                        basePrompt = styleEnricher.enrichTopicPlanningPrompt(accountId, basePrompt);
                    } else {
                        log.debug("[StyleEnricher] variables 中无 accountId，跳过风格画像注入（不阻断选题）");
                    }
                }
                // 注入竞品画像上下文（差异化选题参考）
                if (competitorProfileService != null) {
                    String niche = extractNiche(variables);
                    basePrompt = injectCompetitorContext(basePrompt, niche);
                }
                return basePrompt;
            });
        }

        return builder.build();
    }

    /**
     * 从变量Map中提取accountId。
     */
    private String extractAccountId(Object variables) {
        if (variables instanceof java.util.Map<?, ?> map) {
            Object id = map.get("accountId");
            return id != null ? id.toString() : null;
        }
        return null;
    }

    /**
     * 从变量Map中提取niche（领域）。
     */
    private String extractNiche(Object variables) {
        if (variables instanceof java.util.Map<?, ?> map) {
            Object niche = map.get("accountNiche");
            return niche != null ? niche.toString() : null;
        }
        return null;
    }

    /**
     * 注入竞品画像上下文到选题Prompt。
     */
    private String injectCompetitorContext(String prompt, String niche) {
        if (niche == null || niche.isBlank()) {
            return prompt;
        }
        try {
            var competitors = competitorProfileService.listCompetitors(niche);
            if (competitors == null || competitors.isEmpty()) {
                return prompt;
            }
            StringBuilder sb = new StringBuilder(prompt);
            sb.append("\n\n## 竞品画像参考（定向监控数据）\n");
            sb.append("当前领域已有 ").append(competitors.size()).append(" 个竞品画像，请在选题时参考以下信息：\n");
            sb.append("- 优先选择竞品未覆盖的差异化选题方向\n");
            sb.append("- 参考竞品高表现内容的共性特征（标题风格、切入角度）\n");
            sb.append("- 避免与竞品选题高度重叠\n");
            return sb.toString();
        } catch (Exception e) {
            return prompt; // 降级：竞品画像不可用时不影响选题
        }
    }
}
