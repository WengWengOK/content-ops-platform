package com.contentops.common.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

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
     * 进程内兜底缓存：Redis 不可用时保证 ChatMemory 仍可用（内存态），
     * 避免 LangChain4j 的 MessageWindowChatMemory.messages() 因 store 返回空列表
     * 而抛出 "messages cannot be null or empty"（P0 修复）。
     * Redis 恢复后以 Redis 为准（兜底缓存仅用于降级读取）。
     */
    private final Map<String, List<ChatMessage>> fallbackCache = new ConcurrentHashMap<>();

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
                // Redis 可达但无数据：清空兜底缓存，避免读到降级期的陈旧数据
                fallbackCache.put(key, new ArrayList<>());
                log.debug("ChatMemory: no messages found for memoryId={}", memoryId);
                return new ArrayList<>();
            }
            List<StoredMessage> stored = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, StoredMessage.class));
            List<ChatMessage> messages = new ArrayList<>();
            for (StoredMessage s : stored) {
                messages.add(fromStored(s));
            }
            fallbackCache.put(key, new ArrayList<>(messages));
            log.debug("ChatMemory: loaded {} messages for memoryId={}", messages.size(), memoryId);
            return messages;
        } catch (Exception e) {
            log.warn("ChatMemory: Redis 不可用，使用内存兜底 memoryId={}: {}", memoryId, e.getMessage());
            return new ArrayList<>(fallbackCache.getOrDefault(key, new ArrayList<>()));
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = keyPrefix + memoryId.toString();
        // 先更新兜底缓存（即使 Redis 写入失败，本进程内仍可读取）
        fallbackCache.put(key, new ArrayList<>(messages));
        try {
            String json = objectMapper.writeValueAsString(messages.stream().map(this::toStored).toList());
            try {
                redisTemplate.opsForValue().set(key, json, ttl);
                log.debug("ChatMemory: stored {} messages for memoryId={}", messages.size(), memoryId);
            } catch (Exception e) {
                log.warn("ChatMemory: Redis 不可用，仅保存在内存兜底 memoryId={}: {}", memoryId, e.getMessage());
            }
        } catch (Exception e) {
            // LangChain4j 的 ChatMessage 接口无法被 Jackson 直接序列化，
            // 这里统一转成 StoredMessage DTO 后写入（P0 修复：聊天记忆此前从未真正持久化）。
            log.error("ChatMemory: 消息序列化失败 memoryId={}", memoryId, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = keyPrefix + memoryId.toString();
        fallbackCache.remove(key);
        try {
            redisTemplate.delete(key);
            log.debug("ChatMemory: deleted messages for memoryId={}", memoryId);
        } catch (Exception e) {
            log.warn("ChatMemory: Redis 不可用，已清理内存兜底 memoryId={}: {}", memoryId, e.getMessage());
        }
    }

    // ──────────────── LangChain4j ChatMessage ↔ 可序列化 DTO ────────────────

    /**
     * 持久化 DTO：只保存消息类型、文本与可选的用户名，
     * 避免直接序列化 LangChain4j 的 ChatMessage 接口（Jackson 无法处理）。
     */
    private record StoredMessage(String type, String text, String name, String id) {
    }

    private StoredMessage toStored(ChatMessage message) {
        return switch (message.type()) {
            case SYSTEM -> new StoredMessage("system", ((SystemMessage) message).text(), null, null);
            case AI -> {
                AiMessage ai = (AiMessage) message;
                yield new StoredMessage("ai", ai.text() != null ? ai.text() : "", null, null);
            }
            case TOOL_EXECUTION_RESULT -> {
                ToolExecutionResultMessage tool = (ToolExecutionResultMessage) message;
                yield new StoredMessage("tool", tool.text(), tool.toolName(), tool.id());
            }
            default -> new StoredMessage("user", userText((UserMessage) message),
                    ((UserMessage) message).name(), null);
        };
    }

    private ChatMessage fromStored(StoredMessage stored) {
        return switch (stored.type()) {
            case "system" -> SystemMessage.from(stored.text());
            case "ai" -> AiMessage.from(stored.text() == null ? "" : stored.text());
            case "tool" -> ToolExecutionResultMessage.from(stored.id(), stored.name(), stored.text());
            default -> UserMessage.from(stored.text());
        };
    }

    private String userText(UserMessage message) {
        if (message.hasSingleText()) {
            return message.singleText();
        }
        return message.contents() == null ? "" : message.contents().toString();
    }
}
