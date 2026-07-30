package com.contentops.orchestrator.gateway;

import com.contentops.analysis.controller.AnalysisAgentController;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.DiscussionResponse;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.TopicPlanResult;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.content.controller.ContentAgentController;
import com.contentops.image.controller.ImageAgentController;
import com.contentops.optimize.controller.OptimizeAgentController;
import com.contentops.publish.controller.PublishAgentController;
import com.contentops.topic.controller.DiscussionController;
import com.contentops.topic.controller.TopicAgentController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 单体模式 Agent 网关 — 直接在进程内调用各 Agent Controller，零网络开销。
 *
 * <p>单体架构下，所有 Agent 运行在同一 JVM 中，通过 Spring 依赖注入直接调用
 * 各 Agent 的 Controller 方法，完全替代原微服务架构中的 Feign 远程调用。
 *
 * <p>优势：
 * <ul>
 *   <li>零网络延迟：本地方法调用，无 HTTP/TCP 开销</li>
 *   <li>无序列化开销：对象直接传递，无需 JSON 序列化反序列化</li>
 *   <li>简化部署：单个 JAR 包，单个进程</li>
 *   <li>便于调试：单步断点可跟踪完整流水线</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalAgentGateway implements AgentGateway {

    private final TopicAgentController topicAgentController;
    private final ContentAgentController contentAgentController;
    private final ImageAgentController imageAgentController;
    private final PublishAgentController publishAgentController;
    private final AnalysisAgentController analysisAgentController;
    private final OptimizeAgentController optimizeAgentController;
    private final DiscussionController discussionController;

    // ══════════════════ 同步调用（本地方法调用） ══════════════════

    @Override
    public AgentResponse<Map<String, Object>> callTopic(AgentTaskRequest request) {
        log.debug("[Local] Calling TopicAgent directly");
        return topicAgentController.execute(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callContentExecute(AgentTaskRequest request) {
        log.debug("[Local] Calling ContentAgent.execute directly");
        return contentAgentController.execute(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callContentOutline(AgentTaskRequest request) {
        log.debug("[Local] Calling ContentAgent.generateOutline directly");
        return contentAgentController.generateOutline(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callContentDraft(AgentTaskRequest request) {
        log.debug("[Local] Calling ContentAgent.generateDraft directly");
        return contentAgentController.generateDraft(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callImageExecute(AgentTaskRequest request) {
        log.debug("[Local] Calling ImageAgent.execute directly");
        return imageAgentController.execute(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callImageStyles(AgentTaskRequest request) {
        log.debug("[Local] Calling ImageAgent.generateStyleDirections directly");
        return imageAgentController.generateStyleDirections(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callImageGenerate(AgentTaskRequest request) {
        log.debug("[Local] Calling ImageAgent.generateImages directly");
        return imageAgentController.generateImages(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callPublish(AgentTaskRequest request) {
        log.debug("[Local] Calling PublishAgent directly");
        return publishAgentController.execute(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callAnalysis(AgentTaskRequest request) {
        log.debug("[Local] Calling AnalysisAgent directly");
        return analysisAgentController.execute(request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callOptimize(AgentTaskRequest request) {
        log.debug("[Local] Calling OptimizeAgent directly");
        return optimizeAgentController.execute(request);
    }

    // ══════════════════ 讨论模式（本地方法调用） ══════════════════

    @Override
    public AgentResponse<DiscussionResponse> startDiscussion(Map<String, Object> request) {
        log.debug("[Local] Calling DiscussionController.startDiscussion directly");
        return discussionController.startDiscussion(request);
    }

    @Override
    public AgentResponse<DiscussionResponse> chatDiscussion(String sessionId, Map<String, Object> request) {
        log.debug("[Local] Calling DiscussionController.chat directly");
        return discussionController.chat(sessionId, request);
    }

    @Override
    public AgentResponse<TopicPlanResult> finalizeDiscussion(String sessionId) {
        log.debug("[Local] Calling DiscussionController.finalize directly");
        return discussionController.finalize(sessionId);
    }

    @Override
    public AgentResponse<DiscussionSession> getDiscussionSession(String sessionId) {
        log.debug("[Local] Calling DiscussionController.getSession directly");
        return discussionController.getSession(sessionId);
    }

    @Override
    public AgentResponse<Void> clearDiscussionSession(String sessionId) {
        log.debug("[Local] Calling DiscussionController.clearSession directly");
        return discussionController.clearSession(sessionId);
    }
}
