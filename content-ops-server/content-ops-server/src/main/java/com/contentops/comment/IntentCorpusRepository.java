package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 意图语料库仓储（Postgres / H2 通用 SQL）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class IntentCorpusRepository {

    private static final String COLS = "corpus_id, owner_id, intent, phrase, source, enabled, hit_count, "
            + "created_at, updated_at";

    private static final String SQL_INSERT = "INSERT INTO contentops_intent_corpus (" + COLS
            + ") VALUES (?,?,?,?,?,?,?,?,?)";
    private static final String SQL_LIST = "SELECT " + COLS + " FROM contentops_intent_corpus "
            + "WHERE enabled = TRUE AND (? = '' OR owner_id IS NULL OR owner_id = ?) "
            + "ORDER BY intent, phrase LIMIT ?";
    private static final String SQL_LIST_ALL = "SELECT " + COLS + " FROM contentops_intent_corpus "
            + "WHERE (? = '' OR owner_id IS NULL OR owner_id = ?) ORDER BY intent, phrase LIMIT ?";
    private static final String SQL_BY_ID = "SELECT " + COLS
            + " FROM contentops_intent_corpus WHERE corpus_id = ?";
    private static final String SQL_COUNT = "SELECT COUNT(1) FROM contentops_intent_corpus";
    private static final String SQL_EXISTS_PHRASE = "SELECT COUNT(1) FROM contentops_intent_corpus "
            + "WHERE intent = ? AND phrase = ? AND (? = '' OR owner_id IS NULL OR owner_id = ?)";
    private static final String SQL_DELETE = "DELETE FROM contentops_intent_corpus WHERE corpus_id = ?";
    private static final String SQL_SET_ENABLED = "UPDATE contentops_intent_corpus SET enabled = ?, "
            + "updated_at = ? WHERE corpus_id = ?";
    private static final String SQL_HIT = "UPDATE contentops_intent_corpus SET hit_count = hit_count + 1 "
            + "WHERE corpus_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public void insert(IntentCorpusEntry entry) {
        jdbcTemplate.update(SQL_INSERT, entry.getCorpusId(), entry.getOwnerId(), entry.getIntent(),
                entry.getPhrase(), entry.getSource() == null ? "USER" : entry.getSource(),
                entry.isEnabled(), entry.getHitCount(),
                Timestamp.valueOf(entry.getCreatedAt() == null ? LocalDateTime.now() : entry.getCreatedAt()),
                Timestamp.valueOf(entry.getUpdatedAt() == null ? LocalDateTime.now() : entry.getUpdatedAt()));
    }

    /** 可用语料（全局 + 指定租户），用于向量匹配缓存。 */
    public List<IntentCorpusEntry> listEnabled(String ownerId) {
        String o = ownerId == null ? "" : ownerId;
        try {
            return jdbcTemplate.query(SQL_LIST, this::mapRow, o, o, 2000);
        } catch (Exception e) {
            log.error("[IntentCorpus] 查询失败", e);
            return List.of();
        }
    }

    public List<IntentCorpusEntry> listAll(String ownerId) {
        String o = ownerId == null ? "" : ownerId;
        try {
            return jdbcTemplate.query(SQL_LIST_ALL, this::mapRow, o, o, 2000);
        } catch (Exception e) {
            log.error("[IntentCorpus] 查询失败", e);
            return List.of();
        }
    }

    public Optional<IntentCorpusEntry> findById(String corpusId) {
        try {
            return jdbcTemplate.query(SQL_BY_ID, this::mapRow, corpusId).stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean exists(String intent, String phrase, String ownerId) {
        String o = ownerId == null ? "" : ownerId;
        try {
            Integer count = jdbcTemplate.queryForObject(SQL_EXISTS_PHRASE, Integer.class, intent, phrase, o, o);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public long count() {
        try {
            Long count = jdbcTemplate.queryForObject(SQL_COUNT, Long.class);
            return count == null ? 0 : count;
        } catch (Exception e) {
            return 0;
        }
    }

    public void delete(String corpusId) {
        jdbcTemplate.update(SQL_DELETE, corpusId);
    }

    public void setEnabled(String corpusId, boolean enabled) {
        jdbcTemplate.update(SQL_SET_ENABLED, enabled, Timestamp.valueOf(LocalDateTime.now()), corpusId);
    }

    public void incrementHit(String corpusId) {
        try {
            jdbcTemplate.update(SQL_HIT, corpusId);
        } catch (Exception e) {
            log.debug("[IntentCorpus] 命中计数失败: {}", e.getMessage());
        }
    }

    private IntentCorpusEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return IntentCorpusEntry.builder()
                .corpusId(rs.getString("corpus_id"))
                .ownerId(rs.getString("owner_id"))
                .intent(rs.getString("intent"))
                .phrase(rs.getString("phrase"))
                .source(rs.getString("source"))
                .enabled(rs.getBoolean("enabled"))
                .hitCount(rs.getInt("hit_count"))
                .createdAt(created == null ? null : created.toLocalDateTime())
                .updatedAt(updated == null ? null : updated.toLocalDateTime())
                .build();
    }
}