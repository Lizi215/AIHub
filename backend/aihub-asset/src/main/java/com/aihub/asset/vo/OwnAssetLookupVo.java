package com.aihub.asset.vo;

import java.util.List;

/**
 * 「我自己的同名资源」查询结果（手动重新上传时用）。
 *
 * <p>用户再次上传同名资源时，前端据此判断应追加新版本（复用已有资源）还是新建资源，
 * 并能在上传前发现"版本号已存在"，而不是等后端报错。
 *
 * @param assetId       已有资源 id
 * @param name          资源名
 * @param status        状态（DRAFT / PUBLISHED / SUSPENDED）
 * @param latestVersion 当前最新版本
 * @param versions      已存在的版本号列表（用于判断用户填的版本是否重复）
 */
public record OwnAssetLookupVo(
        Long assetId,
        String name,
        String status,
        String latestVersion,
        List<String> versions
) {
}
