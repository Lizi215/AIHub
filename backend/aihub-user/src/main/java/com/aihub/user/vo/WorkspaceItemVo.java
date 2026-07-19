package com.aihub.user.vo;

/**
 * Workspace 条目（对外）。
 *
 * @param name      资源名，经 Feign 以 MySQL 为准回查；资产不可用时为 null
 * @param available 资源当前是否仍已发布且版本可下载
 */
public record WorkspaceItemVo(
        Long assetId,
        String name,
        String version,
        boolean available
) {
}
