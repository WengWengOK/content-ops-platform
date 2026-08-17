package com.contentops.publish.config;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.common.prompt.PromptFragmentService;
import com.contentops.common.prompt.PromptVersionService;
import com.contentops.publish.agent.PublishingAgent;
import com.contentops.publish.tool.PublishTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link PublishingAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 *
 * <p><b>P1: 动态 Prompt 拼接</b>——当 {@code contentops.prompt.enabled=true} 时，
 * 通过 {@link PromptFragmentService#assemblePublishSystemMessage} 动态组装系统提示词，
 * 根据文章调性和目标平台追加专属指导片段，并支持 A/B 测试变体。
 * 关闭时回退到 {@link PublishingAgent} 上的 {@code @SystemMessage} 注解。
 */
@Configuration
public class PublishAgentConfig {

    @Bean
    public PublishingAgent publishingAgent(@Qualifier("formattingChatModel") ChatModel chatModel,
                                            PublishTools publishTools,
                                            RedisChatMemoryProvider chatMemoryProvider,
                                            FileTools fileTools,
                                            PromptFragmentService promptFragmentService,
                                            PromptVersionService promptVersionService) {
        var builder = AiServices.builder(PublishingAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider);

        // P1: 动态 Prompt 拼接——启用时用 PromptFragmentService 组装，否则回退到 @SystemMessage
        if (promptVersionService.isDynamicPromptEnabled()) {
            builder.systemMessageProvider(promptFragmentService::assemblePublishSystemMessage);
        }

        return builder.build();
    }
}
