package com.contentops.common.credential;

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
 * 平台账号凭据仓储（Postgres / H2 通用 SQL）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PlatformCredentialRepository {

    private static final String COLS = "credential_id, owner_id, platform, account_name, account_ref, preset, "
            + "base_url, access_token, enabled, is_default, last_used_at, last_error, created_at, updated_at";

    private static final String SQL_INSERT = "INSERT INTO contentops_platform_credential (" + COLS
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_LIST = "SELECT " + COLS + " FROM contentops_platform_credential "
            + "WHERE (? = '' OR owner_id = ?) ORDER BY is_default DESC, created_at DESC LIMIT ?";
    private static final String SQL_BY_ID = "SELECT " + COLS
            + " FROM contentops_platform_credential WHERE credential_id = ?";
    private static final String SQL_ENABLED_BY_PLATFORM = "SELECT " + COLS
            + " FROM contentops_platform_credential WHERE platform = ? AND enabled = TRUE "
            + "ORDER BY is_default DESC, created_at ASC";
    private static final String SQL_DEFAULT = "SELECT " + COLS + " FROM contentops_platform_credential "
            + "WHERE platform = ? AND enabled = TRUE AND is_default = TRUE AND (? = '' OR owner_id = ?) LIMIT 1";
    private static final String SQL_UPDATE = "UPDATE contentops_platform_credential SET account_name = ?, "
            + "account_ref = ?, preset = ?, base_url = ?, access_token = ?, enabled = ?, is_default = ?, "
            + "updated_at = ? WHERE credential_id = ?";
    private static final String SQL_CLEAR_DEFAULT = "UPDATE contentops_platform_credential SET is_default = FALSE "
            + "WHERE platform = ? AND (? = '' OR owner_id = ?) AND credential_id <> ?";
    private static final String SQL_DELETE = "DELETE FROM contentops_platform_credential WHERE credential_id = ?";
    private static final String SQL_MARK_USED = "UPDATE contentops_platform_credential SET last_used_at = ?, "
            + "last_error = ? WHERE credential_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public void insert(PlatformCredential c) {
        jdbcTemplate.update(SQL_INSERT,
                c.getCredentialId(), c.getOwnerId(), c.getPlatform(), c.getAccountName(), c.getAccountRef(),
                c.getPreset(), c.getBaseUrl(), c.getAccessToken(), c.isEnabled(), c.isDefaultCredential(),
                c.getLastUsedAt() == null ? null : Timestamp.valueOf(c.getLastUsedAt()),
                c.getLastError(),
                Timestamp.valueOf(c.getCreatedAt() == null ? LocalDateTime.now() : c.getCreatedAt()),
                Timestamp.valueOf(c.getUpdatedAt() == null ? LocalDateTime.now() : c.getUpdatedAt()));
    }

    public List<PlatformCredential> list(String ownerId, int limit) {
        String o = ownerId == null ? "" : ownerId;
        try {
            return jdbcTemplate.query(SQL_LIST, this::mapRow, o, o, Math.max(1, limit));
        } catch (Exception e) {
            log.error("[Credential] 查询失败", e);
            return List.of();
        }
    }

    public Optional<PlatformCredential> findById(String credentialId) {
        try {
            return jdbcTemplate.query(SQL_BY_ID, this::mapRow, credentialId).stream().findFirst();
        } catch (Exception e) {
            log.error("[Credential] 按 ID 查询失败: {}", credentialId, e);
            return Optional.empty();
        }
    }

    /** 某平台全部启用中的凭据（定时任务按账号逐个拉取通知时使用）。 */
    public List<PlatformCredential> listEnabled(String platform) {
        try {
            return jdbcTemplate.query(SQL_ENABLED_BY_PLATFORM, this::mapRow, platform);
        } catch (Exception e) {
            log.error("[Credential] 查询启用凭据失败: platform={}", platform, e);
            return List.of();
        }
    }

    public Optional<PlatformCredential> findDefault(String platform, String ownerId) {
        String o = ownerId == null ? "" : ownerId;
        try {
            return jdbcTemplate.query(SQL_DEFAULT, this::mapRow, platform, o, o).stream().findFirst();
        } catch (Exception e) {
            log.error("[Credential] 查询默认凭据失败: platform={}", platform, e);
            return Optional.empty();
        }
    }

    public void update(PlatformCredential c) {
        jdbcTemplate.update(SQL_UPDATE, c.getAccountName(), c.getAccountRef(), c.getPreset(),
                c.getBaseUrl(), c.getAccessToken(), c.isEnabled(), c.isDefaultCredential(),
                Timestamp.valueOf(LocalDateTime.now()), c.getCredentialId());
    }

    /** 清掉同租户同平台的其它默认标记。 */
    public void clearDefault(String platform, String ownerId, String exceptCredentialId) {
        String o = ownerId == null ? "" : ownerId;
        jdbcTemplate.update(SQL_CLEAR_DEFAULT, platform, o, o, exceptCredentialId);
    }

    public void delete(String credentialId) {
        jdbcTemplate.update(SQL_DELETE, credentialId);
    }

    public void markUsed(String credentialId, String error) {
        try {
            jdbcTemplate.update(SQL_MARK_USED, Timestamp.valueOf(LocalDateTime.now()),
                    error == null ? null : error.substring(0, Math.min(error.length(), 900)), credentialId);
        } catch (Exception e) {
            log.debug("[Credential] 更新使用记录失败: {}", e.getMessage());
        }
    }

    private PlatformCredential mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return PlatformCredential.builder()
                .credentialId(rs.getString("credential_id"))
                .ownerId(rs.getString("owner_id"))
                .platform(rs.getString("platform"))
                .accountName(rs.getString("account_name"))
                .accountRef(rs.getString("account_ref"))
                .preset(rs.getString("preset"))
                .baseUrl(rs.getString("base_url"))
                .accessToken(rs.getString("access_token"))
                .enabled(rs.getBoolean("enabled"))
                .defaultCredential(rs.getBoolean("is_default"))
                .lastUsedAt(lastUsed == null ? null : lastUsed.toLocalDateTime())
                .lastError(rs.getString("last_error"))
                .createdAt(created == null ? null : created.toLocalDateTime())
                .updatedAt(updated == null ? null : updated.toLocalDateTime())
                .build();
    }
}