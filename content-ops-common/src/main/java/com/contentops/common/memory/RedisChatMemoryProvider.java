package com.contentops.common.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Factory that creates ChatMemory instances backed by Redis.
 *
 * <p>Each Agent receives its own ChatMemoryProvider, which produces a
 * {@link MessageWindowChatMemory} with a configurable window size.
 * The underlying store is {@link RedisChatMemoryStore}, so conversation
 * history persists across restarts and is shared across service instances.
 *
 * <p>Memory isolation: each memoryId is unique per workflow+agent combination
 * (e.g., "topic-planning:workflow-123"), ensuring that one workflow's conversation
 * does not leak into another.
 *
 * <p>Configuration is driven by {@link ChatMemoryProperties}:
 * <pre>
 * contentops:
 *   chat-memory:
 *     window-size: 20
 *     ttl-hours: 24
 *     key-prefix: "contentops:chat-memory:"
 * </pre>
 */
@Slf4j
@Component
public class RedisChatMemoryProvider implements ChatMemoryProvider {

    private final ChatMemoryStore chatMemoryStore;
    private final int windowSize;

    public RedisChatMemoryProvider(StringRedisTemplate redisTemplate,
                                    ChatMemoryProperties properties) {
        this.chatMemoryStore = new RedisChatMemoryStore(
                redisTemplate,
                properties.getKeyPrefix(),
                Duration.ofHours(properties.getTtlHours())
        );
        this.windowSize = properties.getWindowSize();
        log.info("RedisChatMemoryProvider initialized: windowSize={}, ttlHours={}, keyPrefix={}",
                windowSize, properties.getTtlHours(), properties.getKeyPrefix());
    }

    @Override
    public ChatMemory get(Object memoryId) {
        String id = memoryId.toString();
        log.debug("Creating ChatMemory for memoryId={}, windowSize={}", id, windowSize);
        return MessageWindowChatMemory.builder()
                .id(id)
                .maxMessages(windowSize)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /**
     * Creates a ChatMemoryProvider with a custom window size.
     * Useful for agents that need more or fewer context messages.
     *
     * @param customWindowSize max number of messages to retain
     * @return a new ChatMemoryProvider instance
     */
    public ChatMemoryProvider withWindowSize(int customWindowSize) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId.toString())
                .maxMessages(customWindowSize)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /**
     * Returns the underlying store, useful for direct cleanup operations.
     */
    public ChatMemoryStore getStore() {
        return chatMemoryStore;
    }

    /**
     * Clears conversation history for a specific memoryId.
     *
     * @param memoryId the memory identifier (e.g., "topic-planning:workflow-123")
     */
    public void clearMemory(String memoryId) {
        chatMemoryStore.deleteMessages(memoryId);
        log.info("Cleared chat memory for memoryId={}", memoryId);
    }
}
