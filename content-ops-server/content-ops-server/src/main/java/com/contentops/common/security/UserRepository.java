package com.contentops.common.security;

import com.contentops.common.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户表数据访问（contentops_user，见 classpath:schema.sql）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO contentops_user (user_id, username, password_hash, password_salt, role) "
                    + "VALUES (?, ?, ?, ?, 'CREATOR')";
    private static final String SQL_SELECT_BY_USERNAME =
            "SELECT user_id, username, password_hash, password_salt, role "
                    + "FROM contentops_user WHERE username = ?";
    private static final String SQL_SELECT_BY_ID =
            "SELECT user_id, username, password_hash, password_salt, role "
                    + "FROM contentops_user WHERE user_id = ?";
    private static final String SQL_LIST =
            "SELECT user_id, username, role, created_at FROM contentops_user ORDER BY created_at DESC";
    private static final String SQL_UPDATE_ROLE =
            "UPDATE contentops_user SET role = ? WHERE user_id = ?";

    /**
     * 创建用户；用户名已存在返回 false。
     */
    public boolean create(String userId, String username, String passwordHash, String passwordSalt) {
        try {
            jdbcTemplate.update(SQL_INSERT, userId, username, passwordHash, passwordSalt);
            return true;
        } catch (DuplicateKeyException e) {
            log.warn("[Auth] 用户名已存在: {}", username);
            return false;
        } catch (Exception e) {
            log.error("[Auth] 创建用户失败: {}", username, e);
            return false;
        }
    }

    /**
     * 按 user_id 查询用户（含 Caffeine 本地缓存，供 JWT 校验快速判断用户是否存在）。
     */
    @Cacheable(value = CacheConfig.CACHE_USERS, key = "#userId")
    public Optional<UserRecord> findById(String userId) {
        try {
            List<UserRecord> rows = jdbcTemplate.query(SQL_SELECT_BY_ID,
                    (rs, i) -> new UserRecord(
                            rs.getString("user_id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("password_salt"),
                            rs.getString("role")),
                    userId);
            return rows.stream().findFirst();
        } catch (Exception e) {
            log.error("[Auth] 查询用户失败: userId={}", userId, e);
            return Optional.empty();
        }
    }

    public List<Map<String, Object>> listAll() {
        try {
            return jdbcTemplate.queryForList(SQL_LIST);
        } catch (Exception e) {
            log.error("[Auth] 用户列表查询失败", e);
            return List.of();
        }
    }

    @CacheEvict(value = CacheConfig.CACHE_USERS, key = "#userId")
    public boolean updateRole(String userId, String role) {
        try {
            return jdbcTemplate.update(SQL_UPDATE_ROLE, role, userId) > 0;
        } catch (Exception e) {
            log.error("[Auth] 更新角色失败: userId={}", userId, e);
            return false;
        }
    }

    /**
     * 创建用户并清除用户缓存（避免新用户/重复写入后缓存不一致）。
     */
    @CacheEvict(value = CacheConfig.CACHE_USERS, allEntries = true)
    public boolean createWithCacheEvict(String userId, String username,
                                        String passwordHash, String passwordSalt) {
        return create(userId, username, passwordHash, passwordSalt);
    }

    public Optional<UserRecord> findByUsername(String username) {
        try {
            List<UserRecord> rows = jdbcTemplate.query(SQL_SELECT_BY_USERNAME,
                    (rs, i) -> new UserRecord(
                            rs.getString("user_id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("password_salt"),
                            rs.getString("role")),
                    username);
            return rows.stream().findFirst();
        } catch (Exception e) {
            log.error("[Auth] 查询用户失败: {}", username, e);
            return Optional.empty();
        }
    }

    public record UserRecord(String userId, String username, String passwordHash,
                             String passwordSalt, String role) {
    }
}
