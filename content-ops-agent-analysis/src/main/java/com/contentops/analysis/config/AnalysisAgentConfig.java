package com.contentops.analysis.config;

import com.contentops.analysis.agent.DataAnalysisAgent;
import com.contentops.analysis.tool.AnalysisTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link DataAnalysisAgent} LangChain4j AI Service bean.
 *
 * <p>The {@link ChatModel} bean is auto-configured by
 * {@code langchain4j-open-ai-spring-boot-starter} (see {@code langchain4j.open-ai.chat-model.*}
 * in {@code application.yml}). Here it is wired together with the
 * {@link AnalysisTools} so the model can call the analysis tools during performance analysis.
 */
@Configuration
public class AnalysisAgentConfig {

    @Bean
    public DataAnalysisAgent dataAnalysisAgent(ChatModel chatModel,
                                                AnalysisTools analysisTools) {
        return AiServices.builder(DataAnalysisAgent.class)
                .chatModel(chatModel)
                .tools(analysisTools)
                .build();
    }
}
