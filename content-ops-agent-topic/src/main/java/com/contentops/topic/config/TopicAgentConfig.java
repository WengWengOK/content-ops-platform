package com.contentops.topic.config;

import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.topic.agent.TopicPlanningAgent;
import com.contentops.topic.tool.TopicResearchTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link TopicPlanningAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 *
 * <p>The {@link ChatModel} bean is auto-configured by
 * {@code langchain4j-open-ai-spring-boot-starter} (see {@code langchain4j.open-ai.chat-model.*}
 * in {@code application.yml}). Here it is wired together with the
 * {@link TopicResearchTools} and {@link RedisChatMemoryProvider} so the agent
 * can recall previous conversation turns within the same workflow.
 *
 * <p>Memory isolation: the {@code @MemoryId} parameter on
 * {@link TopicPlanningAgent#planTopics} uses the format
 * {@code "topic-planning:{workflowId}"}, ensuring each workflow has its own
 * conversation history stored in Redis.
 */
@Configuration
public class TopicAgentConfig {

    @Bean
    public TopicPlanningAgent topicPlanningAgent(ChatModel chatModel,
                                                  TopicResearchTools topicResearchTools,
                                                  RedisChatMemoryProvider chatMemoryProvider) {
        return AiServices.builder(TopicPlanningAgent.class)
                .chatModel(chatModel)
                .tools(topicResearchTools)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
