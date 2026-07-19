package com.aihub.user.controller;

import com.aihub.api.dto.UserBrief;
import com.aihub.api.user.UserQueryApi;
import com.aihub.common.result.Result;
import com.aihub.user.service.UserService;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户只读查询的内部接口，实现 {@link UserQueryApi} 契约。
 *
 * <p>路径 {@code /internal/v1/users/**}，Gateway 未配置路由，不暴露到公网。
 */
@RestController
public class InternalUserQueryController implements UserQueryApi {

    /** 单次批量上限，id 列表会进 URL，过大既有长度风险也无意义 */
    private static final int MAX_IDS = 200;

    private final UserService userService;

    public InternalUserQueryController(UserService userService) {
        this.userService = userService;
    }

    @Override
    public Result<List<UserBrief>> briefs(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.ok(List.of());
        }
        List<Long> capped = ids.size() > MAX_IDS ? ids.subList(0, MAX_IDS) : ids;
        return Result.ok(userService.briefsByIds(capped));
    }
}
