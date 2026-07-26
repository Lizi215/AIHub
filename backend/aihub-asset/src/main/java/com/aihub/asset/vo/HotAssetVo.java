package com.aihub.asset.vo;

/**
 * 热门榜条目（对外）。
 *
 * @param name  资源名。榜单只存 id（Redis ZSet），名字以 MySQL 为准回查，
 *              这样资源改名后榜单上的名字也是新的
 */
public record HotAssetVo(Long assetId, String name, double score) {
}
