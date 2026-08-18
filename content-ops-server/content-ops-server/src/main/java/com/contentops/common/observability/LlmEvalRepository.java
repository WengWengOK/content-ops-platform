package com.contentops.common.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * LLM-as-Judge 评测存储（contentops_llm_eval_case / contentops_llm_eval_run）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LlmEvalRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT_CASE =
            "INSERT INTO contentops_llm_eval_case (case_id, stage, title, input_ref, expected, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SQL_INSERT_RUN =
            "INSERT INTO contentops_llm_eval_run "
                    + "(run_id, case_id, workflow_id, stage, model, judge_score, judge_feedback, passed, threshold, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SQL_RUNS =
            "SELECT run_id, case_id, workflow_id, stage, model, judge_score, judge_feedback, "
                    + "passed, threshold, created_at FROM contentops_llm_eval_run "
                    + "WHERE (? IS NULL OR ? = '' OR stage = ?) "
                    + "ORDER BY created_at DESC LIMIT ?";
    private static final String SQL_LATEST_RUN_BY_WORKFLOW =
            "SELECT run_id, case_id, workflow_id, stage, model, judge_score, judge_feedback, "
                    + "passed, threshold, created_at FROM contentops_llm_eval_run "
                    + "WHERE workflow_id = ? "
                    + "ORDER BY created_at DESC LIMIT 1";
    private static final String SQL_LATEST_FAILING_RUN =
            "SELECT run_id, case_id, workflow_id, stage, model, judge_score, judge_feedback, "
                    + "passed, threshold, created_at FROM contentops_llm_eval_run "
                    + "WHERE workflow_id = ? AND passed = FALSE "
                    + "ORDER BY created_at DESC LIMIT 1";
    private static final String SQL_CASES =
            "SELECT case_id, stage, title, input_ref, expected, created_at FROM contentops_llm_eval_case "
                    + "WHERE (? IS NULL OR ? = '' OR stage = ?) "
                    + "ORDER BY created_at DESC LIMIT ?";

    public void insertCase(String caseId, String stage, String title,
                           String inputRef, String expected, Timestamp createdAt) {
        try {
            jdbcTemplate.update(SQL_INSERT_CASE, caseId, stage, title, inputRef, expected, createdAt);
        } catch (Exception e) {
            log.warn("[Eval] 保存评测用例失败: {}", e.getMessage());
        }
    }

    public void insertRun(String runId, String caseId, String workflowId, String stage, String model,
                          Integer score, String feedback, boolean passed, int threshold, Timestamp createdAt) {
        try {
            jdbcTemplate.update(SQL_INSERT_RUN,
                    runId, caseId, workflowId, stage, model, score, feedback, passed, threshold, createdAt);
        } catch (Exception e) {
            log.warn("[Eval] 保存评测结果失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> findRuns(String stage, int limit) {
        try {
            String s = stage == null ? "" : stage;
            return jdbcTemplate.queryForList(SQL_RUNS, s, s, s, limit);
        } catch (Exception e) {
            log.error("[Eval] 查询评测结果失败", e);
            return List.of();
        }
    }

    public List<Map<String, Object>> findCases(String stage, int limit) {
        try {
            String s = stage == null ? "" : stage;
            return jdbcTemplate.queryForList(SQL_CASES, s, s, s, limit);
        } catch (Exception e) {
            log.error("[Eval] 查询评测用例失败", e);
            return List.of();
        }
    }

    /**
     * 查询指定工作流的最新一次评测记录（无论是否通过）。
     * 自我改进闭环在"工作流 COMPLETED 事件"触发后调用此接口，
     * 用于判断"该工作流所有阶段最新一次评测的总览"。
     *
     * @return 若存在记录返回 Map 字段（run_id/workflow_id/stage/judge_score/judge_feedback/passed/threshold/created_at）；否则空 Optional
     */
    public java.util.Optional<Map<String, Object>> findLatestRunByWorkflow(String workflowId) {
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(SQL_LATEST_RUN_BY_WORKFLOW, workflowId);
            return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
        } catch (Exception e) {
            log.error("[Eval] 查询工作流最新评测失败 workflowId={}", workflowId, e);
            return java.util.Optional.empty();
        }
    }

    /**
     * 查询指定工作流最近一次评测失败（passed=FALSE）的记录。
     * 自我改进闭环判定"是否该触发优化"的核心接口。
     *
     * @return 若存在最近一次失败记录返回 Map；否则空 Optional
     */
    public java.util.Optional<Map<String, Object>> findLatestFailingRun(String workflowId) {
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(SQL_LATEST_FAILING_RUN, workflowId);
            return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
        } catch (Exception e) {
            log.error("[Eval] 查询工作流失败评测失败 workflowId={}", workflowId, e);
            return java.util.Optional.empty();
        }
    }
}
