package com.contentops.content.config;

import com.contentops.content.agent.ContentCreationAgent;
import com.contentops.content.tool.ContentTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link ContentCreationAgent} LangChain4j AI Service bean.
 *
 * <p>The {@link ChatModel} bean is auto-configured by
 * {@code langchain4j-open-ai-spring-boot-starter} (see {@code langchain4j.open-ai.chat-model.*}
 * in {@code application.yml}). Here it is wired together with the
 * {@link ContentTools} so the model can call the content tools during draft creation.
 */
@Configuration
public class ContentAgentConfig {

    @Bean
    public ContentCreationAgent contentCreationAgent(ChatModel chatModel,
                                                      ContentTools contentTools) {
        return AiServices.builder(ContentCreationAgent.class)
                .chatModel(chatModel)
                .tools(contentTools)
                .build();
    }
}
