package com.aihub.user.controller;

import com.aihub.common.auth.RequireLogin;
import com.aihub.common.result.Result;
import com.aihub.common.trace.UserContext;
import com.aihub.user.dto.UpdateRoleRequest;
import com.aihub.user.service.UserService;
import com.aihub.user.vo.UserVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户信息接口。
 *
 * <p>鉴权由 Gateway 与自定义 JWT 拦截器完成，这里只声明 {@link RequireLogin}。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @RequireLogin
    public Result<UserVo> me() {
        return Result.ok(userService.toVo(userService.getActiveUser(UserContext.getUserId())));
    }

    /**
     * 变更用户角色（Admin only）。
     *
     * <p>角色随 JWT 签发，被变更的用户需重新登录才能拿到新角色。
     */
    @PutMapping("/{id}/role")
    @RequireLogin
    public Result<String> updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        return Result.ok(userService.updateRole(id, request.role(), UserContext.getUserId()));
    }
}
