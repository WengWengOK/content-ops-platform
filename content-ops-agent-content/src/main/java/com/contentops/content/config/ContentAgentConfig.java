package com.contentops.content.config;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.common.prompt.PromptFragmentService;
import com.contentops.common.prompt.PromptVersionService;
import com.contentops.content.agent.ContentCreationAgent;
import com.contentops.content.tool.ContentTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
@Configuration
public class ContentAgentConfig {

    @Bean
    public ContentCreationAgent contentCreationAgent(ChatModel chatModel,
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
        if (promptVersionService.isDynamicPromptEnabled()) {
            builder.systemMessageProvider(promptFragmentService::assembleContentSystemMessage);
        }

        return builder.build();
    }
}
