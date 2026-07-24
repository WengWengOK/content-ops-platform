package com.contentops.common.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed implementation of LangChain4j ChatMemoryStore.
 *
 * <p>Stores conversation history per memoryId (typically {agentCode}:{workflowId}).
 * Messages are serialized as JSON arrays in Redis with a configurable TTL.
 *
 * <p>This enables multi-turn dialog: each Agent can recall previous turns within the
 * same workflow, implementing the "讨论对象" (discussion partner) pattern from the
 * original TRAE Work methodology.
 *
 * <p>Redis key structure:
 * <pre>
 *   contentops:chat-memory:{memoryId}
 *     → JSON array of ChatMessage objects
 *     → TTL: 24 hours (configurable via ChatMemoryProperties)
 * </pre>
 */
@Slf4j
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    /**
     * Creates a store with default settings.
     *
     * @param redisTemplate the Redis template for string operations
     * @param keyPrefix     Redis key prefix (e.g., "contentops:chat-memory:")
     * @param ttl           TTL for stored messages
     */
    public RedisChatMemoryStore(StringRedisTemplate redisTemplate,
                                 String keyPrefix,
                                 Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = keyPrefix + memoryId.toString();
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                log.debug("ChatMemory: no messages found for memoryId={}", memoryId);
                return new ArrayList<>();
            }
            List<ChatMessage> messages = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ChatMessage.class));
            log.debug("ChatMemory: loaded {} messages for memoryId={}", messages.size(), memoryId);
            return messages;
        } catch (Exception e) {
            log.error("ChatMemory: failed to load messages for memoryId={}", memoryId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = keyPrefix + memoryId.toString();
        try {
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(key, json, ttl);
            log.debug("ChatMemory: stored {} messages for memoryId={}", messages.size(), memoryId);
        } catch (Exception e) {
            log.error("ChatMemory: failed to store messages for memoryId={}", memoryId, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = keyPrefix + memoryId.toString();
        redisTemplate.delete(key);
        log.debug("ChatMemory: deleted messages for memoryId={}", memoryId);
    }
}
