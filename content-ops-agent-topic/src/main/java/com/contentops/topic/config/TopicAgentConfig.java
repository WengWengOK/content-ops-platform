package com.contentops.topic.config;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.common.prompt.PromptFragmentService;
import com.contentops.common.prompt.PromptVersionService;
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
 * <p><b>P1: 动态 Prompt 拼接</b>——当 {@code contentops.prompt.enabled=true} 时，
 * 通过 {@link PromptFragmentService#assembleTopicSystemMessage} 动态组装系统提示词，
 * 根据账号画像（领域、调性、平台）追加专属指导片段，并支持 A/B 测试变体。
 * 关闭时回退到 {@link TopicPlanningAgent} 上的 {@code @SystemMessage} 注解。
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
                                                  RedisChatMemoryProvider chatMemoryProvider,
                                                  FileTools fileTools,
                                                  PromptFragmentService promptFragmentService,
                                                  PromptVersionService promptVersionService) {
        var builder = AiServices.builder(TopicPlanningAgent.class)
                .chatModel(chatModel)
                .tools(topicResearchTools, fileTools)
                .chatMemoryProvider(chatMemoryProvider);

        // P1: 动态 Prompt 拼接——启用时用 PromptFragmentService 组装，否则回退到 @SystemMessage
        if (promptVersionService.isDynamicPromptEnabled()) {
            builder.systemMessageProvider(promptFragmentService::assembleTopicSystemMessage);
        }

        return builder.build();
    }
}
