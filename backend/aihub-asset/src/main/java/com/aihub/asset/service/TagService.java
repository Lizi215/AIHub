package com.aihub.asset.service;

import com.aihub.asset.vo.TagVo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 标签（docs/ARCHITECTURE.md §3：tag 字典 + asset_tag 关联，Tag 只服务 Asset）。
 */
public interface TagService {

    /**
     * 创建标签（幂等：同名已存在时返回既有 id，避免"查到没有再创建"的并发重复）。
     */
    Long create(String name);

    /** 全部标签（字典量小，不分页）。 */
    List<TagVo> list();

    /**
     * 整体设置资源的标签（owner only）：
     * 软删既有关联后按名单重建，字典里缺失的名字自动创建。
     *
     * @return 设置后的标签名列表
     */
    List<String> setAssetTags(Long assetId, List<String> names, Long ownerId);

    /**
     * 追加标签（并入已有，而不是替换）。给 Agent「给我的资源打标签」用：
     * 用户说"加上 ai 这个标签"时不应把原有标签抹掉。
     *
     * @return 合并后的最终标签列表
     */
    List<String> addAssetTags(Long assetId, List<String> names, Long ownerId);

    /** 按 id 取资源名（写标签的回执里带上名字，便于前端展示"改了哪个资源"）。 */
    String nameOf(Long assetId);

    /** 资源的标签名列表（公开读）。 */
    List<String> tagsOf(Long assetId);

    /**
     * 批量取多个资源的标签名（列表页用，避免逐行 N+1 查询）。
     *
     * @return assetId -> 标签名列表；不在结果里的 assetId 表示没有标签
     */
    Map<Long, List<String>> tagsOfAssets(Collection<Long> assetIds);
}
