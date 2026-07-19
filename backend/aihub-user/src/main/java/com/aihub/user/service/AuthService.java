package com.aihub.user.service;

import com.aihub.user.dto.LoginRequest;
import com.aihub.user.dto.RegisterRequest;
import com.aihub.user.vo.LoginVo;

/**
 * 认证与令牌生命周期。
 */
public interface AuthService {

    /**
     * 注册。
     *
     * @return 新用户 id
     */
    Long register(RegisterRequest request);

    /**
     * 登录并签发 access / refresh token。
     *
     * @param clientIp 客户端 IP，用于按 IP 维度的防爆破限流
     */
    LoginVo login(LoginRequest request, String clientIp);

    /**
     * 用 refresh token 换新令牌，旧 refresh token 立即失效。
     */
    LoginVo refresh(String refreshToken);

    /**
     * 注销：access token 写入黑名单直至自然过期，refresh token 从 Redis 删除。
     *
     * @param accessToken  当前 access token
     * @param refreshToken 可空；提供时一并撤销
     */
    void logout(String accessToken, String refreshToken);
}
