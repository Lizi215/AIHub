package com.aihub.common.auth;

import java.time.Instant;

/**
 * 校验通过的 token 载荷。
 *
 * @param userId    用户 id（雪花 ID）
 * @param role      角色，refresh token 中为 null
 * @param jwtId     token 唯一标识，注销时写入 Redis 黑名单
 * @param expiresAt 过期时刻，注销时用于计算黑名单 key 的 TTL
 */
public record AuthenticatedUser(Long userId, String role, String jwtId, Instant expiresAt) {
}
