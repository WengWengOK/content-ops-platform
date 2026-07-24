package com.contentops.topic.config;

import com.contentops.topic.agent.TopicPlanningAgent;
import com.contentops.topic.tool.TopicResearchTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link TopicPlanningAgent} LangChain4j AI Service bean.
 *
 * <p>The {@link ChatModel} bean is auto-configured by
 * {@code langchain4j-open-ai-spring-boot-starter} (see {@code langchain4j.open-ai.chat-model.*}
 * in {@code application.yml}). Here it is wired together with the
 * {@link TopicResearchTools} so the model can call the research tools during planning.
 */
@Configuration
public class TopicAgentConfig {

    @Bean
    public TopicPlanningAgent topicPlanningAgent(ChatModel chatModel,
                                                  TopicResearchTools topicResearchTools) {
        return AiServices.builder(TopicPlanningAgent.class)
                .chatModel(chatModel)
                .tools(topicResearchTools)
                .build();
    }
}
