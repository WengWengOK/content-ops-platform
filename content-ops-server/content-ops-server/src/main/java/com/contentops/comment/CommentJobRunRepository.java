package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * 评论采集任务运行记录仓储。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CommentJobRunRepository {

    private static final String COLS = "run_id, trigger_type, started_at, finished_at, works_scanned, "
            + "comments_collected, comments_new, analyzed, failed, source, detail";

    private static final String SQL_INSERT =
            "INSERT INTO contentops_comment_job_run (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_LATEST =
            "SELECT " + COLS + " FROM contentops_comment_job_run ORDER BY started_at DESC LIMIT 1";

    private final JdbcTemplate jdbcTemplate;

    public void insert(CommentJobRun run) {
        try {
            jdbcTemplate.update(SQL_INSERT,
                    run.getRunId(), run.getTriggerType(),
                    run.getStartedAt() == null ? null : Timestamp.valueOf(run.getStartedAt()),
                    run.getFinishedAt() == null ? null : Timestamp.valueOf(run.getFinishedAt()),
                    run.getWorksScanned(), run.getCommentsCollected(), run.getCommentsNew(),
                    run.getAnalyzed(), run.getFailed(), run.getSource(), run.getDetail());
        } catch (Exception e) {
            log.warn("[Comment] 写入任务运行记录失败: runId={}, err={}", run.getRunId(), e.getMessage());
        }
    }

    public Optional<CommentJobRun> latest() {
        try {
            return jdbcTemplate.query(SQL_LATEST, this::mapRow).stream().findFirst();
        } catch (Exception e) {
            log.warn("[Comment] 查询最近任务运行记录失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private CommentJobRun mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return CommentJobRun.builder()
                .runId(rs.getString("run_id"))
                .triggerType(rs.getString("trigger_type"))
                .startedAt(started == null ? null : started.toLocalDateTime())
                .finishedAt(finished == null ? null : finished.toLocalDateTime())
                .worksScanned(rs.getInt("works_scanned"))
                .commentsCollected(rs.getInt("comments_collected"))
                .commentsNew(rs.getInt("comments_new"))
                .analyzed(rs.getInt("analyzed"))
                .failed(rs.getInt("failed"))
                .source(rs.getString("source"))
                .detail(rs.getString("detail"))
                .build();
    }
}