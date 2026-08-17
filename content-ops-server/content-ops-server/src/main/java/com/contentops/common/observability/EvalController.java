package com.contentops.common.observability;

import com.contentops.common.dto.AgentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LLM-as-Judge 评测接口：评估集用例 + 判分记录 + 手动评测。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/observability/evals")
@RequiredArgsConstructor
@Tag(name = "LLM 评测")
public class EvalController {

    private final LlmJudgeService llmJudgeService;
    private final LlmEvalRepository repository;

    @GetMapping("/runs")
    @Operation(summary = "评测判分记录（按阶段过滤）")
    public AgentResponse<Map<String, Object>> runs(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        int safeLimit = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<Map<String, Object>> runs = repository.findRuns(stage, safeLimit);
        return AgentResponse.success("evals", Map.of("total", runs.size(), "runs", runs));
    }

    @GetMapping("/cases")
    @Operation(summary = "评估集用例列表（回归门禁数据源）")
    public AgentResponse<Map<String, Object>> cases(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        int safeLimit = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<Map<String, Object>> cases = repository.findCases(stage, safeLimit);
        return AgentResponse.success("evals", Map.of("total", cases.size(), "cases", cases));
    }

    @PostMapping("/judge")
    @Operation(summary = "手动判分（LLM-as-Judge）")
    public AgentResponse<Map<String, Object>> judge(@RequestBody Map<String, Object> request) {
        String stage = String.valueOf(request.getOrDefault("stage", "manual"));
        String input = request.get("input") == null ? "" : String.valueOf(request.get("input"));
        String output = request.get("output") == null ? "" : String.valueOf(request.get("output"));
        String workflowId = request.get("workflowId") == null ? null : String.valueOf(request.get("workflowId"));
        return AgentResponse.success("evals", llmJudgeService.judge(stage, input, output, workflowId));
    }
}
