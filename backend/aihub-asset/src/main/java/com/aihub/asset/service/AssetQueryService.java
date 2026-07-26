package com.aihub.asset.service;

import com.aihub.api.dto.AssetIndexDoc;
import com.aihub.api.dto.AssetQueryDetail;
import com.aihub.api.dto.AssetQueryItem;
import com.aihub.api.dto.DependencyNodeItem;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;

import java.util.List;

/**
 * 面向 Agent 的内部**只读**查询（实现 {@link com.aihub.api.asset.AssetQueryApi}）。
 *
 * <p>与 {@link AssetService} 分开，是因为可见性语义和返回类型都不同：这里的查询固定以
 * 匿名视角执行，只返回已发布资源；返回的是 {@code aihub-api} 的服务间 DTO，而非对外 VO。
 */
public interface AssetQueryService {

    /**
     * 按关键字 / 分类检索已发布资源。
     *
     * <p>关键字走 MySQL {@code LIKE}（名称 / 简介），是 ES 全文检索落地前的临时实现。
     */
    PageResult<AssetQueryItem> search(String keyword, String category, String tag, PageQuery query);

    /**
     * 某用户名下的全部资源（含草稿），创建时间倒序，最多 50 条。
     * 供 Agent 的「我的资源」工具：ownerId 来自任务创建时的登录态，不是模型参数。
     */
    List<AssetQueryItem> mine(Long ownerId);

    /** 资源详情（含最新版本 README 与全部版本）。 */
    AssetQueryDetail detail(Long assetId);

    /** 依赖树。 */
    List<DependencyNodeItem> dependencyTree(Long assetId);

    /**
     * 增量扫描**应进索引**的资源（已发布），按更新时间升序分页（实现 {@code AssetIndexSourceApi}）。
     *
     * @param updatedAfterIso ISO-8601 时间；为空表示从头全量扫描（索引重建时用）
     */
    PageResult<AssetIndexDoc> scanPublished(String updatedAfterIso, PageQuery query);

    /**
     * 增量扫描**应从索引中移除**的资源 id（非已发布：草稿/已下架/已删除）。
     */
    PageResult<String> scanRemoved(String updatedAfterIso, PageQuery query);
}
