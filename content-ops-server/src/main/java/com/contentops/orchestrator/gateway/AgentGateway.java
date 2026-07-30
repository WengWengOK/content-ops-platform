package com.contentops.orchestrator.gateway;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.DiscussionResponse;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.TopicPlanResult;
import com.contentops.common.event.AgentTaskRequest;

import java.util.Map;

/**
 * Agent 调用网关 — 抽象 Agent 调用方式，支持微服务和单体两种模式。
 *
 * <p>通过 {@code contentops.mode} 配置切换：
 * <ul>
 *   <li>{@code microservice}（默认）：通过 Feign 远程调用各 Agent 微服务</li>
 *   <li>{@code mock}：本地 Mock 实现，用于开发测试，无需启动其他服务</li>
 * </ul>
 *
 * <p>所有 Agent 调用统一通过此接口，业务层不感知底层通信方式。
 */
public interface AgentGateway {

    // ══════════════════ 同步调用 ══════════════════

    AgentResponse<Map<String, Object>> callTopic(AgentTaskRequest request);

    AgentResponse<Map<String, Object>> callContentExecute(AgentTaskRequest request);

    AgentResponse<Map<String, Object>> callContentOutline(AgentTaskRequest request);

    AgentResponse<Map<String, Object>> callContentDraft(AgentTaskRequest request);

    AgentResponse<Map<String, Object>> callImageExecute(AgentTaskRequest request);

    AgentResponse<Map<String, Object>> callImageStyles(AgentTaskRequest request);

    AgentResponse<Map<String, Object>> callImageGenerate(AgentTaskRequest request);

    AgentResponse<Map<String, Object>> callPublish(AgentTaskRequest request);

    AgentResponse<Map<String, Object>> callAnalysis(AgentTaskRequest request);

    AgentResponse<Map<String, Object>> callOptimize(AgentTaskRequest request);

    // ══════════════════ 讨论模式 ══════════════════

    AgentResponse<DiscussionResponse> startDiscussion(Map<String, Object> request);

    AgentResponse<DiscussionResponse> chatDiscussion(String sessionId, Map<String, Object> request);

    AgentResponse<TopicPlanResult> finalizeDiscussion(String sessionId);

    AgentResponse<DiscussionSession> getDiscussionSession(String sessionId);

    AgentResponse<Void> clearDiscussionSession(String sessionId);
}
