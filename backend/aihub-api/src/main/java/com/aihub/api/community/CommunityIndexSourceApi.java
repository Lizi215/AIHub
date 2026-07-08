package com.aihub.api.community;

import com.aihub.api.dto.IssueIndexDoc;
import com.aihub.api.dto.PostIndexDoc;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Community 侧的「索引数据源」内部契约，供 Search Service 的对账任务使用。
 *
 * <p>与 {@link com.aihub.api.asset.AssetIndexSourceApi} 同样的取舍：Search 不读别人的表，
 * 而是经契约按 updated_at 增量取数，自己与 ES 比对后补齐差异。
 *
 * <p>路径在 {@code /internal/v1} 下且不配 Gateway 路由，不会暴露到公网。
 */
@FeignClient(name = "aihub-community", contextId = "communityIndexSourceApi")
public interface CommunityIndexSourceApi {

    /** 增量扫描应进索引的帖子（全部类型；帖子没有草稿态，创建即可见）。 */
    @GetMapping("/internal/v1/index-source/posts")
    Result<PageResult<PostIndexDoc>> scanPosts(@RequestParam(value = "updatedAfter", required = false) String updatedAfter,
                                              @RequestParam("page") Integer page,
                                              @RequestParam("size") Integer size);

    /** 增量扫描应从索引移除的帖子 id（软删除的帖子）。 */
    @GetMapping("/internal/v1/index-source/posts/removed")
    Result<PageResult<String>> scanRemovedPosts(@RequestParam(value = "updatedAfter", required = false) String updatedAfter,
                                                @RequestParam("page") Integer page,
                                                @RequestParam("size") Integer size);

    /** 增量扫描应进索引的 Issue。 */
    @GetMapping("/internal/v1/index-source/issues")
    Result<PageResult<IssueIndexDoc>> scanIssues(@RequestParam(value = "updatedAfter", required = false) String updatedAfter,
                                                @RequestParam("page") Integer page,
                                                @RequestParam("size") Integer size);

    /** 增量扫描应从索引移除的 Issue id（软删除的 Issue）。 */
    @GetMapping("/internal/v1/index-source/issues/removed")
    Result<PageResult<String>> scanRemovedIssues(@RequestParam(value = "updatedAfter", required = false) String updatedAfter,
                                                 @RequestParam("page") Integer page,
                                                 @RequestParam("size") Integer size);
}
