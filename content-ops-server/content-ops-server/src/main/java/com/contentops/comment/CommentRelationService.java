package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论者关系标签（身份分类）服务。
 *
 * <p>标签语义与来源：
 * <ul>
 *   <li>{@code FAN}：粉丝 —— 来自「新增关注」通知（connections），可自动维护；</li>
 *   <li>{@code FOLLOWING}：我关注的人 —— 桥当前不提供该关系，支持手工/外部关系源写入；</li>
 *   <li>{@code FRIEND}：好友（互关）—— 同上，手工或外部关系源写入；</li>
 *   <li>{@code REGULAR}：常客 —— 按评论次数自动判定（默认 ≥ 3 次）；</li>
 *   <li>{@code SELF}：自己的运营账号；</li>
 *   <li>{@code STRANGER}：路人（默认）。</li>
 * </ul>
 *
 * <p>判定优先级：显式标签（手工/粉丝同步）→ 自己 → 常客 → 路人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentRelationService {

    public static final String FAN = "FAN";
    public static final String FOLLOWING = "FOLLOWING";
    public static final String FRIEND = "FRIEND";
    public static final String REGULAR = "REGULAR";
    public static final String SELF = "SELF";
    public static final String STRANGER = "STRANGER";

    private final CommentUserRepository userRepository;
    private final CommentProperties properties;

    public String userKey(String platform, String userId) {
        return (platform == null ? "xiaohongshu" : platform) + ":" + userId;
    }

    /** 记录一次评论并返回该评论者的关系标签。 */
    public String touchAndResolve(String ownerId, String platform, String userId, String nickname,
                                  boolean self) {
        if (isBlank(userId)) {
            return self ? SELF : STRANGER;
        }
        String key = userKey(platform, userId);
        userRepository.upsert(key, ownerId, platform, userId, nickname);
        return resolve(ownerId, platform, userId, self);
    }

    /** 解析关系标签（不写库）。 */
    public String resolve(String ownerId, String platform, String userId, boolean self) {
        if (isBlank(userId)) {
            return self ? SELF : STRANGER;
        }
        String key = userKey(platform, userId);
        String explicit = userRepository.relationOf(key);
        if (!isBlank(explicit) && !STRANGER.equals(explicit)) {
            return explicit;
        }
        if (self) {
            return SELF;
        }
        Integer count = userRepository.commentCount(key);
        int threshold = Math.max(2, properties.getRelation().getRegularThreshold());
        if (count != null && count >= threshold) {
            return REGULAR;
        }
        return STRANGER;
    }

    /** 「新增关注」通知 → 标记为粉丝（自动维护 FAN）。 */
    public boolean markFollower(String ownerId, String platform, String userId, String nickname) {
        if (isBlank(userId)) {
            return false;
        }
        String key = userKey(platform, userId);
        userRepository.upsert(key, ownerId, platform, userId, nickname);
        userRepository.setRelation(key, FAN, "CONNECTIONS", "来自「新增关注」通知");
        return true;
    }

    /** 手工/外部关系源设置标签（FOLLOWING / FRIEND / FAN / ...）。 */
    public boolean setRelation(String ownerId, String platform, String userId, String relation, String note) {
        if (isBlank(userId) || isBlank(relation)) {
            return false;
        }
        String key = userKey(platform, userId);
        userRepository.upsert(key, ownerId, platform, userId, null);
        userRepository.setRelation(key, relation.trim().toUpperCase(), "MANUAL", note);
        log.info("[CommentRelation] 手动设置关系: key={}, relation={}", key, relation);
        return true;
    }

    /** 关系分布统计 + 用户列表。 */
    public Map<String, Object> overview(String ownerId, String relation, int limit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.getRelation().isEnabled());
        data.put("regularThreshold", properties.getRelation().getRegularThreshold());
        data.put("byRelation", userRepository.statsByRelation(ownerId));
        data.put("users", userRepository.list(ownerId, relation == null ? "" : relation, limit));
        return data;
    }

    public List<Map<String, Object>> users(String ownerId, String relation, int limit) {
        return userRepository.list(ownerId, relation == null ? "" : relation, limit);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}