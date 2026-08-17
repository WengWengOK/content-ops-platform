package com.contentops.common.security;

/**
 * 用户角色（RBAC）：
 * <ul>
 *   <li>ADMIN — 管理员：用户管理、审计查询、系统配置</li>
 *   <li>CREATOR — 创作者：工作流/热点/作品等业务功能（默认）</li>
 *   <li>VIEWER — 只读：仅可查看，不可变更</li>
 * </ul>
 */
public enum UserRole {
    ADMIN,
    CREATOR,
    VIEWER;

    /** 安全解析：非法值返回 null */
    public static UserRole valueOfSafe(String name) {
        if (name == null) {
            return null;
        }
        try {
            return UserRole.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
