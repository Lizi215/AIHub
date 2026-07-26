package com.aihub.asset.controller;

import com.aihub.api.asset.AssetIndexSourceApi;
import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.dto.AssetIndexDoc;
import com.aihub.api.dto.AssetQueryDetail;
import com.aihub.api.dto.AssetQueryItem;
import com.aihub.api.dto.DependencyNodeItem;
import com.aihub.asset.service.AssetQueryService;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 内部只读查询接口，实现 {@link AssetQueryApi} 契约。
 *
 * <p>路径 {@code /internal/v1/**} 不在 Gateway 路由表中，不会暴露到公网（docs/SECURITY.md §3）；
 * 这里没有任何写接口——Agent 的写入必须经用户确认，落库责任在业务服务自己的对外接口上。
 *
 * <p>映射注解在实现方法上重复声明，避免「路径对了但参数绑不上」这类只在跨服务调用时才暴露的问题。
 */
@RestController
public class InternalAssetQueryController implements AssetQueryApi, AssetIndexSourceApi {

    private final AssetQueryService assetQueryService;

    public InternalAssetQueryController(AssetQueryService assetQueryService) {
        this.assetQueryService = assetQueryService;
    }

    @Override
    @GetMapping("/internal/v1/query/assets")
    public Result<PageResult<AssetQueryItem>> search(@RequestParam(value = "keyword", required = false) String keyword,
                                                     @RequestParam(value = "category", required = false) String category,
                                                     @RequestParam(value = "tag", required = false) String tag,
                                                     @RequestParam(value = "page", required = false) Integer page,
                                                     @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(assetQueryService.search(keyword, category, tag, new PageQuery(page, size)));
    }

    @Override
    @GetMapping("/internal/v1/query/assets/mine")
    public Result<List<AssetQueryItem>> mine(@RequestParam("ownerId") Long ownerId) {
        return Result.ok(assetQueryService.mine(ownerId));
    }

    @Override
    @GetMapping("/internal/v1/query/assets/{assetId}")
    public Result<AssetQueryDetail> detail(@PathVariable("assetId") Long assetId) {
        return Result.ok(assetQueryService.detail(assetId));
    }

    @Override
    @GetMapping("/internal/v1/query/assets/{assetId}/dependency-tree")
    public Result<List<DependencyNodeItem>> dependencyTree(@PathVariable("assetId") Long assetId) {
        return Result.ok(assetQueryService.dependencyTree(assetId));
    }

    /* ------------------------------------------------- 索引数据源（供 Search 对账） */

    @Override
    @GetMapping("/internal/v1/index-source/assets")
    public Result<PageResult<AssetIndexDoc>> scanPublished(
            @RequestParam(value = "updatedAfter", required = false) String updatedAfter,
            @RequestParam("page") Integer page,
            @RequestParam("size") Integer size) {
        return Result.ok(assetQueryService.scanPublished(updatedAfter, new PageQuery(page, size)));
    }

    @Override
    @GetMapping("/internal/v1/index-source/assets/removed")
    public Result<PageResult<String>> scanRemoved(
            @RequestParam(value = "updatedAfter", required = false) String updatedAfter,
            @RequestParam("page") Integer page,
            @RequestParam("size") Integer size) {
        return Result.ok(assetQueryService.scanRemoved(updatedAfter, new PageQuery(page, size)));
    }
}
