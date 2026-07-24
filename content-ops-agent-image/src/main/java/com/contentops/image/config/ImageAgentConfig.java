package com.contentops.image.config;

import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.image.agent.ImageDesignAgent;
import com.contentops.image.tool.ImageTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link ImageDesignAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 */
@Configuration
public class ImageAgentConfig {

    @Bean
    public ImageDesignAgent imageDesignAgent(ChatModel chatModel,
                                              ImageTools imageTools,
                                              RedisChatMemoryProvider chatMemoryProvider) {
        return AiServices.builder(ImageDesignAgent.class)
                .chatModel(chatModel)
                .tools(imageTools)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
