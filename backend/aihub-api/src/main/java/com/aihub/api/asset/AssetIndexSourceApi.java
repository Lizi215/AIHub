package com.aihub.api.asset;

import com.aihub.api.dto.AssetIndexDoc;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Asset 侧的「索引数据源」内部契约，供 Search Service 的对账任务使用。
 *
 * <p>不复用 {@code AssetQueryApi}：那个契约是给 Agent 的交互式查询，对账需要的是按 updated_at
 * 增量扫描，语义与用途都不同，混在一起会让两边的改动互相牵制。
 *
 * <p>对账由 Search 拉数据而非业务服务主动推：只有同时看得到 MySQL 与 ES 的一方才能做
 * 「按 updated_at 增量比对记录是否存在且版本一致」的对比，也就是持有 ES 出口的 Search Service。
 * 它经本契约取数，不直接读 Asset 的表。
 *
 * <p>路径挂在 {@code /internal/v1} 下且不配 Gateway 路由，不会暴露到公网。
 */
@FeignClient(name = "aihub-asset", contextId = "assetIndexSourceApi")
public interface AssetIndexSourceApi {

    /**
     * 增量扫描应进索引的资源（即已发布），按更新时间升序分页。
     *
     * @param updatedAfter ISO-8601 时间；不传表示从头全量扫描（对账重建时用）
     * @param page         页码，从 1 开始
     * @param size         每页条数
     */
    @GetMapping("/internal/v1/index-source/assets")
    Result<PageResult<AssetIndexDoc>> scanPublished(@RequestParam(value = "updatedAfter", required = false) String updatedAfter,
                                                   @RequestParam("page") Integer page,
                                                   @RequestParam("size") Integer size);

    /**
     * 增量扫描应当从索引中移除的资源 id（即已发布但后来下架/删除的）。
     *
     * <p>没有这个接口，"删除后 ES 文档同步移除"就只能靠全量比对，代价是每次都要拉全表。
     */
    @GetMapping("/internal/v1/index-source/assets/removed")
    Result<PageResult<String>> scanRemoved(@RequestParam(value = "updatedAfter", required = false) String updatedAfter,
                                           @RequestParam("page") Integer page,
                                           @RequestParam("size") Integer size);
}
