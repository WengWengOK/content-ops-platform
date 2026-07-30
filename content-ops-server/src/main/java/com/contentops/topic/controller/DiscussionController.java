package com.contentops.topic.controller;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.DiscussionResponse;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.dto.TopicPlanResult;
import com.contentops.topic.agent.DiscussionAgent;
import com.contentops.topic.service.DiscussionSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.ArrayList;
import java.util.List;

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
                .map(session -> AgentResponse.success("discussion", session))
                .orElseGet(() -> AgentResponse.failure("discussion",
                        "Discussion session not found: " + sessionId));
    }

    /**
     * Clear the session and its conversation memory.
     */
    @DeleteMapping("/{sessionId}")
    public AgentResponse<Void> clearSession(@PathVariable String sessionId) {
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
