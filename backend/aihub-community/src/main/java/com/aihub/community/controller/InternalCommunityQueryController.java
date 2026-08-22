package com.aihub.community.controller;

import com.aihub.api.community.CommunityQueryApi;
import com.aihub.api.dto.IssueQueryItem;
import com.aihub.api.dto.PostQueryItem;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.community.service.CommunityQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 内部只读查询接口，实现 {@link CommunityQueryApi} 契约。
 *
 * <p>路径 {@code /internal/v1/**} 不配 Gateway 路由，不会暴露到公网；且无任何写接口。
 */
@RestController
public class InternalCommunityQueryController implements CommunityQueryApi {

    private final CommunityQueryService communityQueryService;

    public InternalCommunityQueryController(CommunityQueryService communityQueryService) {
        this.communityQueryService = communityQueryService;
    }

    @Override
    @GetMapping("/internal/v1/query/community/issues")
    public Result<PageResult<IssueQueryItem>> listIssues(@RequestParam("assetId") Long assetId,
                                                         @RequestParam(value = "status", required = false) String status,
                                                         @RequestParam(value = "page", required = false) Integer page,
                                                         @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(communityQueryService.listIssues(assetId, status, new PageQuery(page, size)));
    }

    @Override
    @GetMapping("/internal/v1/query/community/posts")
    public Result<PageResult<PostQueryItem>> listPosts(@RequestParam(value = "type", required = false) String type,
                                                       @RequestParam(value = "page", required = false) Integer page,
                                                       @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(communityQueryService.listPosts(type, new PageQuery(page, size)));
    }
}
