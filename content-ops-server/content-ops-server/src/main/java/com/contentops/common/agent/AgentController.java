package com.contentops.common.agent;

import com.contentops.common.dto.AgentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 平台控制面接口：注册表查询 + 事件总线查询（审计/回放）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
@Tag(name = "Agent 平台控制面")
public class AgentController {

    private final AgentRegistry agentRegistry;
    private final AgentEventRepository eventRepository;

    @GetMapping
    @Operation(summary = "Agent 注册表（控制面）：平台全部 Agent 元数据")
    public AgentResponse<Map<String, Object>> registry() {
        List<AgentDescriptor> agents = agentRegistry.all();
        return AgentResponse.success("agents", Map.of(
                "total", agents.size(),
                "agents", agents));
    }

    @GetMapping("/events")
    @Operation(summary = "Agent 事件总线查询（Outbox 审计/回放）")
    public AgentResponse<Map<String, Object>> events(
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        int safeLimit = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<Map<String, Object>> events = eventRepository.findRecent(workflowId, agent, safeLimit);
        return AgentResponse.success("agents", Map.of(
                "total", events.size(),
                "events", events));
    }
}
