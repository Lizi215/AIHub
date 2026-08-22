package com.aihub.community.controller;

import com.aihub.api.community.CommunityIndexSourceApi;
import com.aihub.api.dto.IssueIndexDoc;
import com.aihub.api.dto.PostIndexDoc;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.community.service.CommunityIndexSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 索引数据源接口，实现 {@link CommunityIndexSourceApi} 契约（供 Search 的对账任务取数）。
 *
 * <p>路径 {@code /internal/v1/**} 不配 Gateway 路由，不会暴露到公网；只读，无写接口。
 */
@RestController
public class InternalCommunityIndexSourceController implements CommunityIndexSourceApi {

    private final CommunityIndexSourceService indexSourceService;

    public InternalCommunityIndexSourceController(CommunityIndexSourceService indexSourceService) {
        this.indexSourceService = indexSourceService;
    }

    @Override
    @GetMapping("/internal/v1/index-source/posts")
    public Result<PageResult<PostIndexDoc>> scanPosts(
            @RequestParam(value = "updatedAfter", required = false) String updatedAfter,
            @RequestParam("page") Integer page,
            @RequestParam("size") Integer size) {
        return Result.ok(indexSourceService.scanPosts(updatedAfter, new PageQuery(page, size)));
    }

    @Override
    @GetMapping("/internal/v1/index-source/posts/removed")
    public Result<PageResult<String>> scanRemovedPosts(
            @RequestParam(value = "updatedAfter", required = false) String updatedAfter,
            @RequestParam("page") Integer page,
            @RequestParam("size") Integer size) {
        return Result.ok(indexSourceService.scanRemovedPosts(updatedAfter, new PageQuery(page, size)));
    }

    @Override
    @GetMapping("/internal/v1/index-source/issues")
    public Result<PageResult<IssueIndexDoc>> scanIssues(
            @RequestParam(value = "updatedAfter", required = false) String updatedAfter,
            @RequestParam("page") Integer page,
            @RequestParam("size") Integer size) {
        return Result.ok(indexSourceService.scanIssues(updatedAfter, new PageQuery(page, size)));
    }

    @Override
    @GetMapping("/internal/v1/index-source/issues/removed")
    public Result<PageResult<String>> scanRemovedIssues(
            @RequestParam(value = "updatedAfter", required = false) String updatedAfter,
            @RequestParam("page") Integer page,
            @RequestParam("size") Integer size) {
        return Result.ok(indexSourceService.scanRemovedIssues(updatedAfter, new PageQuery(page, size)));
    }
}
