package com.contentops.common.observability;

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
 * LLM 可观测性接口：trace 查询 + token/成本/延迟统计。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/observability/llm")
@RequiredArgsConstructor
@Tag(name = "LLM 可观测性")
public class ObservabilityController {

    private final LlmTraceService llmTraceService;

    @GetMapping("/traces")
    @Operation(summary = "最近 LLM 调用追踪（可按 stage/agent/workflow 过滤）")
    public AgentResponse<Map<String, Object>> traces(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) Integer limit) {
        List<LlmTrace> traces = llmTraceService.traces(stage, agent, workflowId, limit);
        return AgentResponse.success("observability", Map.of(
                "total", traces.size(),
                "traces", traces));
    }

    @GetMapping("/stats")
    @Operation(summary = "LLM 调用统计：token/延迟/错误/估算成本 + 阶段排行 + 小时时序")
    public AgentResponse<Map<String, Object>> stats(
            @RequestParam(required = false, defaultValue = "24") Integer hours) {
        return AgentResponse.success("observability", llmTraceService.stats(hours));
    }

}
