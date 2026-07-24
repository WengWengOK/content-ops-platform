package com.contentops.optimize.config;

import com.contentops.optimize.agent.OptimizationAgent;
import com.contentops.optimize.tool.OptimizeTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link OptimizationAgent} LangChain4j AI Service bean.
 *
 * <p>The {@link ChatModel} bean is auto-configured by
 * {@code langchain4j-open-ai-spring-boot-starter} (see {@code langchain4j.open-ai.chat-model.*}
 * in {@code application.yml}). Here it is wired together with the
 * {@link OptimizeTools} so the model can call the optimization tools during strategy adjustment.
 */
@Configuration
public class OptimizeAgentConfig {

    @Bean
    public OptimizationAgent optimizationAgent(ChatModel chatModel,
                                                OptimizeTools optimizeTools) {
        return AiServices.builder(OptimizationAgent.class)
                .chatModel(chatModel)
                .tools(optimizeTools)
                .build();
    }
}
