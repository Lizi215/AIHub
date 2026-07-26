package com.aihub.asset.vo;

/**
 * 直接依赖项。
 *
 * @param dependencyId   依赖关系记录 id（删除时用）
 * @param assetId        被依赖资源 id
 * @param name           被依赖资源名，便于展示
 * @param latestVersion  被依赖资源的最新版本
 * @param versionConstraint 版本约束
 */
public record AssetDependencyVo(
        Long dependencyId,
        Long assetId,
        String name,
        String latestVersion,
        String versionConstraint
) {
}
