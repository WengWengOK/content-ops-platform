package com.contentops.publish.config;

import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.publish.agent.PublishingAgent;
import com.contentops.publish.tool.PublishTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link PublishingAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 */
@Configuration
public class PublishAgentConfig {

    @Bean
    public PublishingAgent publishingAgent(ChatModel chatModel,
                                            PublishTools publishTools,
                                            RedisChatMemoryProvider chatMemoryProvider) {
        return AiServices.builder(PublishingAgent.class)
                .chatModel(chatModel)
                .tools(publishTools)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
