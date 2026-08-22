package com.aihub.community.controller;

import com.aihub.common.auth.RequireLogin;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.common.trace.UserContext;
import com.aihub.community.dto.CreateCommentRequest;
import com.aihub.community.dto.CreateIssueRequest;
import com.aihub.community.service.IssueCommentService;
import com.aihub.community.service.IssueService;
import com.aihub.community.vo.IssueCommentVo;
import com.aihub.community.vo.IssueVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue 接口。
 *
 * <p>读取匿名可访问（社区内容公开）；写入与状态变更需要登录，
 * 状态机与权限由服务层判定。
 */
@RestController
@RequestMapping("/api/v1/issues")
public class IssueController {

    private final IssueService issueService;
    private final IssueCommentService issueCommentService;

    public IssueController(IssueService issueService, IssueCommentService issueCommentService) {
        this.issueService = issueService;
        this.issueCommentService = issueCommentService;
    }

    /** 提出 Issue。 */
    @PostMapping
    @RequireLogin
    public Result<Long> create(@Valid @RequestBody CreateIssueRequest request) {
        return Result.ok(issueService.create(UserContext.getUserId(), request));
    }

    @GetMapping("/{id}")
    public Result<IssueVo> detail(@PathVariable Long id) {
        return Result.ok(issueService.detail(id));
    }

    /** 变更状态。合法流转由 IssueStatusMachine 判定。 */
    @PutMapping("/{id}/status")
    @RequireLogin
    public Result<IssueVo> changeStatus(@PathVariable Long id, @RequestParam String status) {
        return Result.ok(issueService.changeStatus(id, UserContext.getUserId(), status));
    }

    /** 按资源分页列出 Issue。 */
    @GetMapping
    public Result<PageResult<IssueVo>> listByAsset(@RequestParam Long assetId,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return Result.ok(issueService.listByAsset(assetId, new PageQuery(page, size)));
    }

    /* ------------------------------------------------ 评论（讨论） */

    /** 发表评论。讨论是社区行为，需要登录。 */
    @PostMapping("/{id}/comments")
    @RequireLogin
    public Result<Long> comment(@PathVariable Long id, @Valid @RequestBody CreateCommentRequest request) {
        return Result.ok(issueCommentService.create(id, UserContext.getUserId(), request));
    }

    /** 按 Issue 分页列出评论。读取匿名可访问，与 Issue 本身一致。 */
    @GetMapping("/{id}/comments")
    public Result<PageResult<IssueCommentVo>> comments(@PathVariable Long id,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size) {
        return Result.ok(issueCommentService.listByIssue(id, new PageQuery(page, size)));
    }
}
