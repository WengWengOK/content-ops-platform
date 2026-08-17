package com.contentops.topic.service;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.memory.RedisChatMemoryProvider;
import com.contentops.common.security.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages DiscussionSession lifecycle in Redis.
 *
 * <p>Each session is stored under {@code contentops:discussion:{sessionId}}
 * with a 48-hour TTL. The associated ChatMemory (conversation history) is
 * managed separately by {@link RedisChatMemoryProvider} under
 * {@code contentops:chat-memory:discussion:{sessionId}}.
 *
 * <p>Phase transitions are detected by parsing the AI's response prefix
 * (e.g., "【澄清】" → CLARIFICATION, "【完成】" → COMPLETED).
 */
@Slf4j
@Service
public class DiscussionSessionService {

    private static final String SESSION_KEY_PREFIX = AgentConstants.DISCUSSION_SESSION_PREFIX;
    private static final Duration SESSION_TTL = Duration.ofHours(48);
    private static final String MEMORY_ID_PREFIX = "discussion:";

    private final StringRedisTemplate redisTemplate;
    private final RedisChatMemoryProvider chatMemoryProvider;
    private final ObjectMapper objectMapper;

    /**
     * Redis 不可用时的本地降级缓存（兼作本地缓存）。
     * Redis 正常时以 Redis 为准；Redis 异常时自动读写本地，保证开发/演示环境可用。
     */
    private final ConcurrentHashMap<String, DiscussionSession> localSessionCache = new ConcurrentHashMap<>();

    public DiscussionSessionService(StringRedisTemplate redisTemplate,
                                     RedisChatMemoryProvider chatMemoryProvider) {
        this.redisTemplate = redisTemplate;
        this.chatMemoryProvider = chatMemoryProvider;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Create a new discussion session.
     *
     * @param fuzzyIdea the user's initial fuzzy idea
     * @param profile   the account profile for context (may be null)
     * @return the created session
     */
    public DiscussionSession createSession(String fuzzyIdea, AccountProfile profile) {
        return createSession(fuzzyIdea, profile, null);
    }

    /**
     * Create a discussion session, optionally bound to an existing workflow.
     *
     * @param fuzzyIdea  the user's initial fuzzy idea
     * @param profile    the account profile for context (may be null)
     * @param workflowId the work this conversation belongs to; {@code null} for a
     *                   standalone ideation session (workflowId falls back to sessionId)
     * @return the created session
     */
    public DiscussionSession createSession(String fuzzyIdea, AccountProfile profile, String workflowId) {
        String sessionId = UUID.randomUUID().toString();

        DiscussionSession session = DiscussionSession.builder()
                .sessionId(sessionId)
                .ownerId(AuthContext.currentUserId())
                .workflowId(workflowId == null || workflowId.isBlank() ? sessionId : workflowId)
                .phase(DiscussionSession.DiscussionPhase.IDEATION)
                .fuzzyIdea(fuzzyIdea)
                .accountProfile(profile)
                .turns(new ArrayList<>())
                .clarifyingQuestions(new ArrayList<>())
                .proposedDirections(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        saveSession(session);
        log.info("Created discussion session: sessionId={}, fuzzyIdea length={}",
                sessionId, fuzzyIdea != null ? fuzzyIdea.length() : 0);
        return session;
    }

    /**
     * List all discussion sessions that belong to a workflow.
     *
     * <p>Supports both new-style sessions (workflowId field) and legacy sessions
     * where the sessionId itself equals the workflowId.
     */
    public List<DiscussionSession> listSessionsByWorkflow(String workflowId) {
        List<DiscussionSession> result = new ArrayList<>();
        try {
            redisTemplate.scan(ScanOptions.scanOptions()
                            .match(SESSION_KEY_PREFIX + "*")
                            .count(200)
                            .build())
                    .forEachRemaining(key -> {
                        try {
                            String json = redisTemplate.opsForValue().get(key);
                            if (json == null || json.isBlank()) {
                                return;
                            }
                            DiscussionSession session = objectMapper.readValue(json, DiscussionSession.class);
                            if (workflowId.equals(session.getWorkflowId())
                                    || workflowId.equals(session.getSessionId())) {
                                result.add(session);
                                localSessionCache.put(session.getSessionId(), session);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to deserialize discussion session during list: key={}", key);
                        }
                    });
        } catch (Exception e) {
            log.warn("[Discussion] Redis 不可用，降级本地缓存列表: workflowId={}, err={}",
                    workflowId, e.getMessage());
            localSessionCache.values().stream()
                    .filter(s -> workflowId.equals(s.getWorkflowId())
                            || workflowId.equals(s.getSessionId()))
                    .forEach(result::add);
        }
        result.sort(Comparator.comparing(
                DiscussionSession::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /**
     * Retrieve a session by ID.
     */
    public Optional<DiscussionSession> getSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.ofNullable(localSessionCache.get(sessionId));
            }
            DiscussionSession session = objectMapper.readValue(json, DiscussionSession.class);
            localSessionCache.put(sessionId, session);
            return Optional.of(session);
        } catch (Exception e) {
            log.warn("[Discussion] Redis 不可用，降级本地缓存读取: sessionId={}, err={}",
                    sessionId, e.getMessage());
            return Optional.ofNullable(localSessionCache.get(sessionId));
        }
    }

    /**
     * Persist a session to Redis.
     */
    public void saveSession(DiscussionSession session) {
        String key = SESSION_KEY_PREFIX + session.getSessionId();
        session.setUpdatedAt(LocalDateTime.now());
        try {
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
            localSessionCache.put(session.getSessionId(), session);
        } catch (Exception e) {
            log.warn("[Discussion] Redis 不可用，降级本地缓存保存: sessionId={}, err={}",
                    session.getSessionId(), e.getMessage());
            localSessionCache.put(session.getSessionId(), session);
        }
    }

    /**
     * Add a conversation turn to the session.
     */
    public void addTurn(DiscussionSession session, String role, String content) {
        if (session.getTurns() == null) {
            session.setTurns(new ArrayList<>());
        }
        session.getTurns().add(DiscussionSession.DiscussionTurn.builder()
                .role(role)
                .content(content)
                .timestamp(LocalDateTime.now())
                .build());
        saveSession(session);
    }

    /**
     * Update the session phase based on the AI's response.
     *
     * <p>The AI prefixes its response with 【阶段名】. This method parses
     * the prefix and updates the session phase accordingly.
     */
    public DiscussionSession.DiscussionPhase detectPhase(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return DiscussionSession.DiscussionPhase.IDEATION;
        }
        String trimmed = aiResponse.trim();
        if (trimmed.startsWith("【澄清】")) {
            return DiscussionSession.DiscussionPhase.CLARIFICATION;
        } else if (trimmed.startsWith("【提案】")) {
            return DiscussionSession.DiscussionPhase.CONFIRMATION;
        } else if (trimmed.startsWith("【拆解】")) {
            return DiscussionSession.DiscussionPhase.CONFIRMATION;
        } else if (trimmed.startsWith("【完成】")) {
            return DiscussionSession.DiscussionPhase.COMPLETED;
        }
        // Default: keep current phase or infer from turn count
        return null; // null means "don't change"
    }

    /**
     * Update session phase and save.
     */
    public void updatePhase(DiscussionSession session, DiscussionSession.DiscussionPhase phase) {
        if (phase != null) {
            session.setPhase(phase);
            saveSession(session);
        }
    }

    /**
     * Get the memoryId for a discussion session.
     */
    public String getMemoryId(String sessionId) {
        return MEMORY_ID_PREFIX + sessionId;
    }

    /**
     * Check if the discussion can be finalized (phase is CONFIRMATION or COMPLETED).
     */
    public boolean canFinalize(DiscussionSession session) {
        DiscussionSession.DiscussionPhase phase = session.getPhase();
        return phase == DiscussionSession.DiscussionPhase.CONFIRMATION
                || phase == DiscussionSession.DiscussionPhase.COMPLETED;
    }

    /**
     * Clear all conversation memory and session data for a session.
     */
    public void clearSession(String sessionId) {
        String memoryId = getMemoryId(sessionId);
        chatMemoryProvider.clearMemory(memoryId);
        try {
            redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("[Discussion] Redis 不可用，仅清除本地缓存: sessionId={}", sessionId);
        }
        localSessionCache.remove(sessionId);
        log.info("Cleared discussion session and memory: sessionId={}", sessionId);
    }

    /**
     * Mark a session as completed and store the final result.
     */
    public void completeSession(DiscussionSession session,
                                 com.contentops.common.dto.TopicPlanResult result) {
        session.setPhase(DiscussionSession.DiscussionPhase.COMPLETED);
        session.setTopicPlanResult(result);
        saveSession(session);
        log.info("Discussion session completed: sessionId={}, topicCount={}",
                session.getSessionId(),
                result.getTopics() != null ? result.getTopics().size() : 0);
    }
}
