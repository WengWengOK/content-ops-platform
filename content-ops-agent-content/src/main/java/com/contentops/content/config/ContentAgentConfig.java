package com.contentops.content.config;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
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
 */
@Configuration
public class ContentAgentConfig {

    @Bean
    public ContentCreationAgent contentCreationAgent(ChatModel chatModel,
                                                      ContentTools contentTools,
                                                      RedisChatMemoryProvider chatMemoryProvider,
                                                      FileTools fileTools) {
        return AiServices.builder(ContentCreationAgent.class)
                .chatModel(chatModel)
                .tools(contentTools, fileTools)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
