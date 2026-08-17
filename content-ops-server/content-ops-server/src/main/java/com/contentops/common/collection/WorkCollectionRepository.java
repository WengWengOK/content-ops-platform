package com.contentops.common.collection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 作品合集数据访问（contentops_work_collection / contentops_work_collection_item，见 schema.sql）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class WorkCollectionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLUMNS =
            "c.collection_id, c.owner_id, c.name, c.type, c.description, c.created_at, c.updated_at, "
                    + "(SELECT COUNT(*) FROM contentops_work_collection_item i "
                    + " WHERE i.collection_id = c.collection_id) AS work_count";

    private static final String SQL_INSERT =
            "INSERT INTO contentops_work_collection "
                    + "(collection_id, owner_id, name, type, description) VALUES (?, ?, ?, ?, ?)";
    private static final String SQL_UPDATE =
            "UPDATE contentops_work_collection SET name = ?, type = ?, description = ?, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE collection_id = ?";
    private static final String SQL_DELETE =
            "DELETE FROM contentops_work_collection WHERE collection_id = ?";
    private static final String SQL_DELETE_ITEMS =
            "DELETE FROM contentops_work_collection_item WHERE collection_id = ?";
    private static final String SQL_FIND_BY_ID =
            "SELECT " + COLUMNS + " FROM contentops_work_collection c WHERE c.collection_id = ?";
    private static final String SQL_LIST =
            "SELECT " + COLUMNS + " FROM contentops_work_collection c ORDER BY c.updated_at DESC";
    private static final String SQL_LIST_BY_OWNER =
            "SELECT " + COLUMNS + " FROM contentops_work_collection c "
                    + "WHERE c.owner_id = ? ORDER BY c.updated_at DESC";
    private static final String SQL_LIST_WORK_IDS =
            "SELECT workflow_id FROM contentops_work_collection_item WHERE collection_id = ? "
                    + "ORDER BY added_at DESC";
    private static final String SQL_ADD_WORK =
            "INSERT INTO contentops_work_collection_item (collection_id, workflow_id) VALUES (?, ?)";
    private static final String SQL_REMOVE_WORK =
            "DELETE FROM contentops_work_collection_item WHERE collection_id = ? AND workflow_id = ?";
    private static final String SQL_LIST_BY_WORKFLOW =
            "SELECT " + COLUMNS + " FROM contentops_work_collection c "
                    + "JOIN contentops_work_collection_item i ON i.collection_id = c.collection_id "
                    + "WHERE i.workflow_id = ? ORDER BY c.updated_at DESC";

    public boolean create(String collectionId, String ownerId, String name, String type, String description) {
        try {
            jdbcTemplate.update(SQL_INSERT, collectionId, ownerId, name, type, description);
            return true;
        } catch (Exception e) {
            log.error("[Collection] 创建作品合集失败: name={}, type={}", name, type, e);
            return false;
        }
    }

    public int update(String collectionId, String name, String type, String description) {
        return jdbcTemplate.update(SQL_UPDATE, name, type, description, collectionId);
    }

    public void delete(String collectionId) {
        try {
            jdbcTemplate.update(SQL_DELETE_ITEMS, collectionId);
            jdbcTemplate.update(SQL_DELETE, collectionId);
        } catch (Exception e) {
            log.error("[Collection] 删除作品合集失败: collectionId={}", collectionId, e);
        }
    }

    public Optional<WorkCollection> findById(String collectionId) {
        try {
            List<WorkCollection> rows = jdbcTemplate.query(SQL_FIND_BY_ID,
                    (rs, i) -> mapRow(rs.getString("collection_id"), rs), collectionId);
            return rows.stream().findFirst();
        } catch (Exception e) {
            log.error("[Collection] 查询作品合集失败: collectionId={}", collectionId, e);
            return Optional.empty();
        }
    }

    public List<WorkCollection> listAll() {
        return list(SQL_LIST);
    }

    public List<WorkCollection> listByOwner(String ownerId) {
        return list(SQL_LIST_BY_OWNER, ownerId);
    }

    public List<String> listWorkIds(String collectionId) {
        try {
            return jdbcTemplate.query(SQL_LIST_WORK_IDS,
                    (rs, i) -> rs.getString(1), collectionId);
        } catch (Exception e) {
            log.error("[Collection] 查询合集作品失败: collectionId={}", collectionId, e);
            return List.of();
        }
    }

    public boolean addWork(String collectionId, String workflowId) {
        try {
            jdbcTemplate.update(SQL_ADD_WORK, collectionId, workflowId);
            return true;
        } catch (DuplicateKeyException e) {
            return false; // 已存在，幂等
        } catch (Exception e) {
            log.error("[Collection] 作品加入合集失败: collection={}, workflow={}",
                    collectionId, workflowId, e);
            return false;
        }
    }

    public int removeWork(String collectionId, String workflowId) {
        return jdbcTemplate.update(SQL_REMOVE_WORK, collectionId, workflowId);
    }

    public List<WorkCollection> listByWorkflow(String workflowId) {
        return list(SQL_LIST_BY_WORKFLOW, workflowId);
    }

    private List<WorkCollection> list(String sql, Object... args) {
        try {
            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        WorkCollection c = mapRow(rs.getString("collection_id"), rs);
                        return c;
                    }, args);
        } catch (Exception e) {
            log.error("[Collection] 列出作品合集失败", e);
            return List.of();
        }
    }

    private WorkCollection mapRow(String collectionId, java.sql.ResultSet rs) {
        try {
            return WorkCollection.builder()
                    .collectionId(collectionId)
                    .ownerId(rs.getString("owner_id"))
                    .name(rs.getString("name"))
                    .type(rs.getString("type"))
                    .description(rs.getString("description"))
                    .createdAt(rs.getTimestamp("created_at") == null
                            ? null : rs.getTimestamp("created_at").toLocalDateTime())
                    .updatedAt(rs.getTimestamp("updated_at") == null
                            ? null : rs.getTimestamp("updated_at").toLocalDateTime())
                    .workCount(rs.getInt("work_count"))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
