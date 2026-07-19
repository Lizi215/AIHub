package com.aihub.user.controller;

import com.aihub.common.auth.RequireLogin;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.common.trace.UserContext;
import com.aihub.user.dto.CreateWorkspaceRequest;
import com.aihub.user.service.WorkspaceService;
import com.aihub.user.vo.WorkspaceVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 接口。
 *
 * <p>AI 只生成草案，保存动作由用户在这里确认发起，未经确认不得落库。全部接口需登录并按归属隔离。
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    @RequireLogin
    public Result<Long> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        return Result.ok(workspaceService.create(UserContext.getUserId(), request));
    }

    @GetMapping
    @RequireLogin
    public Result<PageResult<WorkspaceVo>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(workspaceService.list(UserContext.getUserId(), new PageQuery(page, size)));
    }

    @GetMapping("/{id}")
    @RequireLogin
    public Result<WorkspaceVo> detail(@PathVariable Long id) {
        return Result.ok(workspaceService.detail(UserContext.getUserId(), id));
    }

    @DeleteMapping("/{id}")
    @RequireLogin
    public Result<Void> delete(@PathVariable Long id) {
        workspaceService.delete(UserContext.getUserId(), id);
        return Result.ok();
    }
}
