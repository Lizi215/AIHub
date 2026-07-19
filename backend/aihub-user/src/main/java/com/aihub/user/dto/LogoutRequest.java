package com.aihub.user.dto;

/**
 * 注销请求。
 *
 * <p>refresh token 必须一并提交：access token 靠黑名单失效，
 * refresh token 不撤销的话，客户端仍能换回新的 access token。
 */
public record LogoutRequest(String refreshToken) {
}
