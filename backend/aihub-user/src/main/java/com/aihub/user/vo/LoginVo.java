package com.aihub.user.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.aihub.common.jackson.NumericLongSerializer;

/**
 * 登录 / 续期响应。
 *
 * @param accessToken  无状态 access token，放 Authorization 头
 * @param refreshToken 落 Redis 的 refresh token，用于续期与主动注销
 * @param expiresIn    access token 有效期（秒）。是时长而非 ID，显式覆盖全局的 Long→String 规则。
 * @param user         当前用户信息
 */
public record LoginVo(
        String accessToken,
        String refreshToken,
        @JsonSerialize(using = NumericLongSerializer.class) long expiresIn,
        UserVo user) {
}
