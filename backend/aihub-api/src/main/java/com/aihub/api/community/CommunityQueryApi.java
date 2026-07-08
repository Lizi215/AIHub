package com.aihub.api.community;

import com.aihub.api.dto.IssueQueryItem;
import com.aihub.api.dto.PostQueryItem;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Community Service 的内部只读查询契约（供 AI Service 的 Community Tool 调用）。
 *
 * <p>与 {@link com.aihub.api.asset.AssetQueryApi} 同样的取舍：契约放 {@code aihub-api}、
 * 路径挂 {@code /internal/v1} 且不配 Gateway 路由、只读无写接口。
 *
 * <p>不做关键字全文检索：社区全文检索依赖 ES（Phase 3 未完成），此处只按资源 / 类型筛选，
 * 避免用 MySQL {@code LIKE} 假装成搜索。
 */
@FeignClient(name = "aihub-community", contextId = "communityQueryApi")
public interface CommunityQueryApi {

    /**
     * 按资源（可选按状态）查询 Issue。
     *
     * @param assetId 资源 id，必填：Issue 都是针对某个资源提出的
     * @param status  OPEN / PROCESSING / CLOSED，可为空表示不限
     */
    @GetMapping("/internal/v1/query/community/issues")
    Result<PageResult<IssueQueryItem>> listIssues(@RequestParam("assetId") Long assetId,
                                                 @RequestParam(value = "status", required = false) String status,
                                                 @RequestParam(value = "page", required = false) Integer page,
                                                 @RequestParam(value = "size", required = false) Integer size);

    /**
     * 按类型查询帖子（Guide / Experience / Question）。
     *
     * @param type EXPERIENCE / GUIDE / QUESTION，可为空表示不限
     */
    @GetMapping("/internal/v1/query/community/posts")
    Result<PageResult<PostQueryItem>> listPosts(@RequestParam(value = "type", required = false) String type,
                                               @RequestParam(value = "page", required = false) Integer page,
                                               @RequestParam(value = "size", required = false) Integer size);
}
