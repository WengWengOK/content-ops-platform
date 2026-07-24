package com.contentops.optimize.config;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.optimize.agent.OptimizationAgent;
import com.contentops.optimize.tool.OptimizeTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link OptimizationAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 */
@Configuration
public class OptimizeAgentConfig {

    @Bean
    public OptimizationAgent optimizationAgent(ChatModel chatModel,
                                                OptimizeTools optimizeTools,
                                                RedisChatMemoryProvider chatMemoryProvider,
                                                FileTools fileTools) {
        return AiServices.builder(OptimizationAgent.class)
                .chatModel(chatModel)
                .tools(optimizeTools, fileTools)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
