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
}
