package com.contentops.image.config;

import com.contentops.image.agent.ImageDesignAgent;
import com.contentops.image.tool.ImageTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link ImageDesignAgent} LangChain4j AI Service bean.
 *
 * <p>The {@link ChatModel} bean is auto-configured by
 * {@code langchain4j-open-ai-spring-boot-starter} (see {@code langchain4j.open-ai.chat-model.*}
 * in {@code application.yml}). Here it is wired together with the
 * {@link ImageTools} so the model can call the image tools during design.
 */
@Configuration
public class ImageAgentConfig {

    @Bean
    public ImageDesignAgent imageDesignAgent(ChatModel chatModel,
                                              ImageTools imageTools) {
        return AiServices.builder(ImageDesignAgent.class)
                .chatModel(chatModel)
                .tools(imageTools)
                .build();
    }
}
