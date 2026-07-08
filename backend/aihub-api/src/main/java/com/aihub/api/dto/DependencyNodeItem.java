package com.aihub.api.dto;

import java.util.List;

/**
 * 依赖树节点（服务间 DTO，供 Agent 的 Workspace Tool 使用）。
 *
 * @param cycle 是否指向祖先节点。数据异常时才会为 true，Agent 必须据此拒绝生成
 *              「含环的依赖方案」，而不是当成正常的资源组合
 */
public record DependencyNodeItem(
        Long assetId,
        String name,
        boolean cycle,
        List<DependencyNodeItem> children) {
}
