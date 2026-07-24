package com.contentops.analysis.config;

import com.contentops.analysis.agent.DataAnalysisAgent;
import com.contentops.analysis.tool.AnalysisTools;
import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link DataAnalysisAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 */
@Configuration
public class AnalysisAgentConfig {

    @Bean
    public DataAnalysisAgent dataAnalysisAgent(ChatModel chatModel,
                                                AnalysisTools analysisTools,
                                                RedisChatMemoryProvider chatMemoryProvider,
                                                FileTools fileTools) {
        return AiServices.builder(DataAnalysisAgent.class)
                .chatModel(chatModel)
                .tools(analysisTools, fileTools)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
