package com.contentops.publish.config;

import com.contentops.publish.agent.PublishingAgent;
import com.contentops.publish.tool.PublishTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link PublishingAgent} LangChain4j AI Service bean.
 *
 * <p>The {@link ChatModel} bean is auto-configured by
 * {@code langchain4j-open-ai-spring-boot-starter} (see {@code langchain4j.open-ai.chat-model.*}
 * in {@code application.yml}). Here it is wired together with the
 * {@link PublishTools} so the model can call the publishing tools during formatting.
 */
@Configuration
public class PublishAgentConfig {

    @Bean
    public PublishingAgent publishingAgent(ChatModel chatModel,
                                            PublishTools publishTools) {
        return AiServices.builder(PublishingAgent.class)
                .chatModel(chatModel)
                .tools(publishTools)
                .build();
    }
}
