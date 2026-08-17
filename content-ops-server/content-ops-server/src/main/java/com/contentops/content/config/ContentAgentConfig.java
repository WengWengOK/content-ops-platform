package com.contentops.content.config;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.common.profile.style.StyleEnricher;
import com.contentops.common.prompt.PromptFragmentService;
import com.contentops.common.prompt.PromptVersionService;
import com.contentops.content.agent.ContentCreationAgent;
import com.contentops.content.tool.ContentTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the {@link ContentCreationAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 *
 * <p>Wired with {@link ChatModel}, {@link ContentTools}, and {@link RedisChatMemoryProvider}
 * so the agent can recall previous conversation turns within the same workflow.
 *
 * <p><b>P1: 动态 Prompt 拼接</b>——当 {@code contentops.prompt.enabled=true} 时，
 * 通过 {@link PromptFragmentService#assembleContentSystemMessage} 动态组装系统提示词，
 * 根据账号画像（领域、调性）追加专属指导片段，并支持 A/B 测试变体。
 * 关闭时回退到 {@link ContentCreationAgent} 上的 {@code @SystemMessage} 注解。
 */
@Slf4j
@Configuration
public class ContentAgentConfig {

    /** 风格注入器（可选注入，未配置时降级为空） */
    @Autowired(required = false)
    private StyleEnricher styleEnricher;

    @Bean
    public ContentCreationAgent contentCreationAgent(@Qualifier("creativeChatModel") ChatModel chatModel,
                                                      ContentTools contentTools,
                                                      RedisChatMemoryProvider chatMemoryProvider,
                                                      FileTools fileTools,
                                                      PromptFragmentService promptFragmentService,
                                                      PromptVersionService promptVersionService) {
        var builder = AiServices.builder(ContentCreationAgent.class)
                .chatModel(chatModel)
                .tools(contentTools, fileTools)
                .chatMemoryProvider(chatMemoryProvider);

        // P1: 动态 Prompt 拼接——启用时用 PromptFragmentService 组装，否则回退到 @SystemMessage
        // P0增强：动态 Prompt 拼接时，额外注入风格画像保持内容风格一致性
        if (promptVersionService.isDynamicPromptEnabled()) {
            builder.systemMessageProvider(variables -> {
                String basePrompt = promptFragmentService.assembleContentSystemMessage(variables);
                // 注入风格画像（让AI生成的内容像创作者自己写的）
                if (styleEnricher != null) {
                    String accountId = extractAccountId(variables);
                    if (accountId != null && !accountId.isBlank()) {
                        basePrompt = styleEnricher.enrichContentCreationPrompt(accountId, basePrompt);
                    } else {
                        log.debug("[StyleEnricher] variables 中无 accountId，跳过风格画像注入（不阻断创作）");
                    }
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
}
