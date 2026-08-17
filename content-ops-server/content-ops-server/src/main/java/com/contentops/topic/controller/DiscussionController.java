package com.contentops.topic.controller;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.DiscussionResponse;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.dto.TopicPlanResult;
import com.contentops.common.security.AuthContext;
import com.contentops.topic.agent.DiscussionAgent;
import com.contentops.topic.service.DiscussionSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST entry point for the Discussion Agent — multi-turn topic ideation.
 *
 * <p>Supports the "把TRAE当讨论对象" (use TRAE as discussion partner) workflow:
 * <ol>
 *   <li>{@code POST /start} — user provides a fuzzy idea, AI responds with clarifying questions</li>
 *   <li>{@code POST /{sessionId}/chat} — iterative conversation turns (AI remembers history)</li>
 *   <li>{@code POST /{sessionId}/finalize} — AI generates a structured TopicPlanResult</li>
 *   <li>{@code GET  /{sessionId}} — retrieve session state</li>
 *   <li>{@code DELETE /{sessionId}} — clear session and conversation memory</li>
 * </ol>
 *
 * <p>The DiscussionAgent uses Redis-backed ChatMemory, so each session's
 * conversation history persists across calls and service restarts.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/discussion")
@RequiredArgsConstructor
public class DiscussionController {

    private final DiscussionAgent discussionAgent;
    private final @Qualifier("streamingDiscussionAgent") DiscussionAgent streamingDiscussionAgent;
    private final DiscussionSessionService sessionService;

    /**
     * Start a new discussion session with a fuzzy idea.
     */
    @PostMapping("/start")
    public AgentResponse<DiscussionResponse> startDiscussion(
            @Valid @RequestBody StartDiscussionRequest request) {

        log.info("Starting discussion session: fuzzyIdea length={}",
                request.getFuzzyIdea() != null ? request.getFuzzyIdea().length() : 0);

        try {
            // Create session
            DiscussionSession session = sessionService.createSession(
                    request.getFuzzyIdea(), request.getAccountProfile());

            String memoryId = sessionService.getMemoryId(session.getSessionId());

            // First turn: send the fuzzy idea to the AI
            String aiReply = discussionAgent.discuss(memoryId, request.getFuzzyIdea());

            // Record turns
            sessionService.addTurn(session, "user", request.getFuzzyIdea());
            sessionService.addTurn(session, "assistant", aiReply);

            // Detect and update phase
            DiscussionSession.DiscussionPhase detectedPhase = sessionService.detectPhase(aiReply);
            sessionService.updatePhase(session, detectedPhase);

            DiscussionResponse response = buildResponse(session, aiReply);

            log.info("Discussion started: sessionId={}, phase={}",
                    session.getSessionId(), session.getPhase());
            return AgentResponse.success("discussion", response);
        } catch (Exception e) {
            log.error("Failed to start discussion", e);
            return AgentResponse.failure("discussion", e.getMessage());
        }
    }

    /**
     * 单体模式适配：从 Map 参数启动讨论会话。
     */
    public AgentResponse<DiscussionResponse> startDiscussion(Map<String, Object> request) {
        StartDiscussionRequest req = new StartDiscussionRequest();
        if (request != null) {
            Object fuzzyIdea = request.get("fuzzyIdea");
            req.setFuzzyIdea(fuzzyIdea != null ? fuzzyIdea.toString() : null);
            Object accountProfile = request.get("accountProfile");
            if (accountProfile instanceof AccountProfile ap) {
                req.setAccountProfile(ap);
            }
        }
        return startDiscussion(req);
    }

    /**
     * Continue the discussion with a new user message.
     */
    @PostMapping("/{sessionId}/chat")
    public AgentResponse<DiscussionResponse> chat(
            @PathVariable String sessionId,
            @Valid @RequestBody ChatRequest request) {

        log.info("Discussion chat: sessionId={}, message length={}",
                sessionId, request.getMessage() != null ? request.getMessage().length() : 0);

        try {
            DiscussionSession session = sessionService.getSession(sessionId)
                    .orElseThrow(() -> new RuntimeException("Discussion session not found: " + sessionId));

            if (!isOwnerAllowed(session)) {
                return AgentResponse.failure("discussion", "无权访问该讨论会话");
            }

            if (session.getPhase() == DiscussionSession.DiscussionPhase.COMPLETED) {
                return AgentResponse.failure("discussion",
                        "Discussion already completed. Call /finalize to get the result or /start to begin a new session.");
            }

            String memoryId = sessionService.getMemoryId(sessionId);

            // Send user message — ChatMemory automatically includes prior context
            String aiReply = discussionAgent.discuss(memoryId, request.getMessage());

            // Record turns
            sessionService.addTurn(session, "user", request.getMessage());
            sessionService.addTurn(session, "assistant", aiReply);

            // Detect and update phase
            DiscussionSession.DiscussionPhase detectedPhase = sessionService.detectPhase(aiReply);
            sessionService.updatePhase(session, detectedPhase);

            DiscussionResponse response = buildResponse(session, aiReply);

            log.info("Discussion chat completed: sessionId={}, phase={}, turns={}",
                    sessionId, session.getPhase(), session.getTurns().size());
            return AgentResponse.success("discussion", response);
        } catch (Exception e) {
            log.error("Discussion chat failed: sessionId={}", sessionId, e);
            return AgentResponse.failure("discussion", e.getMessage());
        }
    }

    /**
     * 流式对话（SSE）：逐 token 推送 AI 回复，前端打字机式展示。
     */
    @GetMapping("/{sessionId}/chat/stream")
    public SseEmitter streamChat(
            @PathVariable String sessionId,
            @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(120_000L);
        try {
            DiscussionSession session = sessionService.getSession(sessionId)
                    .orElseThrow(() -> new RuntimeException("Discussion session not found: " + sessionId));
            if (!isOwnerAllowed(session)) {
                emitter.completeWithError(new RuntimeException("无权访问该讨论会话"));
                return emitter;
            }
            if (session.getPhase() == DiscussionSession.DiscussionPhase.COMPLETED) {
                emitter.completeWithError(new RuntimeException("讨论已完成，请开始新会话"));
                return emitter;
            }
            String memoryId = sessionService.getMemoryId(sessionId);
            try {
                emitter.send(SseEmitter.event().name("phase").data(session.getPhase().name()));
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
            TokenStream tokenStream = streamingDiscussionAgent.discussStream(memoryId, message);
            tokenStream
                    .onPartialResponse(chunk -> {
                        try {
                            emitter.send(SseEmitter.event().name("delta").data(chunk));
                        } catch (Exception ignored) {
                            // 客户端断开，忽略
                        }
                    })
                    .onCompleteResponse(response -> {
                        try {
                            String full = response != null && response.aiMessage() != null
                                    ? response.aiMessage().text() : "";
                            sessionService.addTurn(session, "user", message);
                            sessionService.addTurn(session, "assistant", full);
                            sessionService.updatePhase(session, sessionService.detectPhase(full));
                            emitter.send(SseEmitter.event().name("done").data(full));
                            emitter.complete();
                            log.info("Discussion stream completed: sessionId={}, chars={}",
                                    sessionId, full.length());
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    })
                    .onError(error -> {
                        log.warn("Discussion stream error: sessionId={}, err={}", sessionId, error.getMessage());
                        emitter.completeWithError(error);
                    })
                    .start();
        } catch (Exception e) {
            log.error("Discussion stream failed: sessionId={}", sessionId, e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 单体模式适配：从 Map 参数继续讨论对话。
     */
    public AgentResponse<DiscussionResponse> chat(String sessionId, Map<String, Object> request) {
        ChatRequest req = new ChatRequest();
        if (request != null) {
            Object message = request.get("message");
            req.setMessage(message != null ? message.toString() : null);
        }
        return chat(sessionId, req);
    }

    /**
     * Finalize the discussion into a structured TopicPlanResult.
     */
    @PostMapping("/{sessionId}/finalize")
    public AgentResponse<TopicPlanResult> finalize(
            @PathVariable String sessionId) {

        log.info("Finalizing discussion: sessionId={}", sessionId);

        try {
            DiscussionSession session = sessionService.getSession(sessionId)
                    .orElseThrow(() -> new RuntimeException("Discussion session not found: " + sessionId));

            if (!isOwnerAllowed(session)) {
                return AgentResponse.failure("discussion", "无权访问该讨论会话");
            }

            String memoryId = sessionService.getMemoryId(sessionId);

            // Generate structured plan from conversation history
            TopicPlanResult result = discussionAgent.finalizeTopicPlan(memoryId);

            // Mark session as completed
            sessionService.completeSession(session, result);

            log.info("Discussion finalized: sessionId={}, topicCount={}",
                    sessionId, result.getTopics() != null ? result.getTopics().size() : 0);
            return AgentResponse.success("discussion", result);
        } catch (Exception e) {
            log.error("Discussion finalization failed: sessionId={}", sessionId, e);
            return AgentResponse.failure("discussion", e.getMessage());
        }
    }

    /**
     * Get the current session state.
     */
    @GetMapping("/{sessionId}")
    public AgentResponse<DiscussionSession> getSession(@PathVariable String sessionId) {
        return sessionService.getSession(sessionId)
                .filter(this::isOwnerAllowed)
                .map(session -> AgentResponse.success("discussion", session))
                .orElseGet(() -> AgentResponse.failure("discussion",
                        "Discussion session not found: " + sessionId));
    }

    /**
     * Clear the session and its conversation memory.
     */
    @DeleteMapping("/{sessionId}")
    public AgentResponse<Void> clearSession(@PathVariable String sessionId) {
        DiscussionSession session = sessionService.getSession(sessionId).orElse(null);
        if (session == null || !isOwnerAllowed(session)) {
            return AgentResponse.failure("discussion", "无权访问该讨论会话");
        }
        sessionService.clearSession(sessionId);
        return AgentResponse.success("discussion", null);
    }

    // ──────────────────── Helper methods ────────────────────

    private DiscussionResponse buildResponse(DiscussionSession session, String aiReply) {
        List<String> questions = new ArrayList<>();
        List<String> directions = new ArrayList<>();

        // Extract clarifying questions from the AI reply (lines ending with ?)
        if (session.getPhase() == DiscussionSession.DiscussionPhase.CLARIFICATION
                || session.getPhase() == DiscussionSession.DiscussionPhase.IDEATION) {
            for (String line : aiReply.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.endsWith("?") || trimmed.endsWith("？")) {
                    questions.add(trimmed);
                }
            }
        }

        return DiscussionResponse.builder()
                .sessionId(session.getSessionId())
                .phase(session.getPhase())
                .message(aiReply)
                .clarifyingQuestions(questions)
                .proposedDirections(directions)
                .canFinalize(sessionService.canFinalize(session))
                .turnCount(session.getTurns() != null ? session.getTurns().size() : 0)
                .build();
    }

    /**
     * 归属校验：鉴权关闭（未登录）时放行；鉴权开启时仅允许本人访问。
     */
    private boolean isOwnerAllowed(DiscussionSession session) {
        String currentUserId = AuthContext.currentUserId();
        if (currentUserId == null) {
            return true; // 鉴权未启用
        }
        return currentUserId.equals(session.getOwnerId());
    }

    // ──────────────────── Request DTOs ────────────────────

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class StartDiscussionRequest {
        /** The user's fuzzy/vague idea — 必填 */
        @jakarta.validation.constraints.NotBlank(message = "fuzzyIdea 不能为空")
        private String fuzzyIdea;
        /** Optional account profile for context */
        private AccountProfile accountProfile;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ChatRequest {
        /** The user's message for this turn — 必填 */
        @jakarta.validation.constraints.NotBlank(message = "message 不能为空")
        private String message;
    }
}
