package com.aihub.user.controller;

import com.aihub.common.auth.RequireLogin;
import com.aihub.common.result.Result;
import com.aihub.user.dto.LoginRequest;
import com.aihub.user.dto.LogoutRequest;
import com.aihub.user.dto.RefreshTokenRequest;
import com.aihub.user.dto.RegisterRequest;
import com.aihub.user.service.AuthService;
import com.aihub.user.util.ClientIpResolver;
import com.aihub.user.vo.LoginVo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。注册与登录必须匿名可访问，其余需要有效 token。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginVo> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return Result.ok(authService.login(request, ClientIpResolver.resolve(servletRequest)));
    }

    @PostMapping("/refresh")
    public Result<LoginVo> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Result.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @RequireLogin
    public Result<Void> logout(@RequestBody(required = false) LogoutRequest request,
                               HttpServletRequest servletRequest) {
        // 拦截器已校验 token 并写入 UserContext，这里取原文给 service 解析 jti
        String accessToken = servletRequest.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER_PREFIX.length());
        authService.logout(accessToken, request == null ? null : request.refreshToken());
        return Result.ok();
    }
}
