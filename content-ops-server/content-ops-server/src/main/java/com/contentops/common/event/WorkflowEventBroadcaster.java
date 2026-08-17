package com.contentops.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流阶段事件 SSE 广播：前端通过 GET /api/v1/workflow/{workflowId}/events
 * 订阅，阶段推进时实时收到 STAGE_STARTED / STAGE_COMPLETED / STAGE_FAILED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEventBroadcaster {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String workflowId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时，由客户端断开/完成移除
        subscribers.computeIfAbsent(workflowId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(workflowId, emitter));
        emitter.onTimeout(() -> remove(workflowId, emitter));
        emitter.onError(e -> remove(workflowId, emitter));
        log.info("[Workflow-SSE] 订阅: workflowId={}, subscribers={}",
                workflowId, subscribers.get(workflowId).size());
        return emitter;
    }

    @EventListener
    public void onStageTransition(StageTransitionEvent event) {
        Set<SseEmitter> emitters = subscribers.get(event.getWorkflowId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(event);
            emitters.forEach(emitter -> {
                try {
                    emitter.send(SseEmitter.event().name("stage").data(json));
                } catch (Exception e) {
                    remove(event.getWorkflowId(), emitter);
                }
            });
        } catch (Exception e) {
            log.warn("[Workflow-SSE] 广播失败: workflowId={}, err={}", event.getWorkflowId(), e.getMessage());
        }
    }

    private void remove(String workflowId, SseEmitter emitter) {
        Set<SseEmitter> emitters = subscribers.get(workflowId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                subscribers.remove(workflowId);
            }
        }
    }
}
