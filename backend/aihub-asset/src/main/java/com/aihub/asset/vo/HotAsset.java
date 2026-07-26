package com.aihub.asset.vo;

/**
 * 热门榜条目。
 *
 * @param assetId 资源 id
 * @param score   热度分
 */
public record HotAsset(Long assetId, double score) {
}
