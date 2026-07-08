package com.aihub.common.auth;

/**
 * 角色取值与判定（docs/ARCHITECTURE.md §9.1）。
 *
 * <p>放 common 是因为角色判定跨服务：Asset / Community 都要判断用户能否处理他人资源，
 * 不能各自定义一套字符串字面量。USER / MAINTAINER / ADMIN 为递进关系，用单字段表达；
 * Visitor 即未登录，不落库，因此没有对应取值。
 */
public enum Role {

    USER,
    MAINTAINER,
    ADMIN;

    /**
     * 是否具备跨归属的管理权限。
     *
     * <p>管理自己名下的资源走归属匹配，任何 User 都具备；本方法只回答能否处理他人资源，
     * 即 {@link #MAINTAINER} 及以上。
     */
    public static boolean isPrivileged(String role) {
        return MAINTAINER.name().equals(role) || ADMIN.name().equals(role);
    }
}
