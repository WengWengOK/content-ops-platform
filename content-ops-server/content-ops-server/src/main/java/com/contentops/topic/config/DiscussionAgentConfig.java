package com.contentops.topic.config;

import com.contentops.common.knowledge.FileTools;
import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.topic.agent.DiscussionAgent;
import com.contentops.topic.tool.TopicResearchTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link DiscussionAgent} LangChain4j AI Service bean with Redis-backed ChatMemory.
 *
 * <p>Unlike the pipeline agents (which use parameterized @UserMessage templates),
 * the DiscussionAgent conducts free-form multi-turn dialogue. The
 * {@link RedisChatMemoryProvider} ensures conversation history persists in Redis
 * across turns, keyed by the {@code @MemoryId} parameter.
 *
 * <p>Memory isolation: each discussion session gets its own ChatMemory
 * (e.g., "discussion:session-123"), so conversations don't leak between sessions.
 */
@Configuration
public class DiscussionAgentConfig {

    @Bean
    public DiscussionAgent discussionAgent(@Qualifier("creativeChatModel") ChatModel chatModel,
                                             RedisChatMemoryProvider chatMemoryProvider,
                                             TopicResearchTools topicResearchTools,
                                             FileTools fileTools) {
        return AiServices.builder(DiscussionAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(topicResearchTools, fileTools)
                .build();
    }

    /**
     * 流式讨论 Agent（SSE 打字机输出）：与普通讨论同一套提示/记忆/工具。
     */
    @Bean
    public DiscussionAgent streamingDiscussionAgent(
            @Qualifier("streamingChatModel") StreamingChatModel streamingChatModel,
            RedisChatMemoryProvider chatMemoryProvider,
            TopicResearchTools topicResearchTools,
            FileTools fileTools) {
        return AiServices.builder(DiscussionAgent.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(topicResearchTools, fileTools)
                .build();
    }
}
