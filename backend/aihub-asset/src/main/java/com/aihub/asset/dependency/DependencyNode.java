package com.aihub.asset.dependency;

import java.util.List;

/**
 * 依赖树节点。
 *
 * @param assetId  资源 id
 * @param cycle    该节点是否指向了祖先节点（数据里存在环）；遇到环只标记不展开，避免递归不收敛
 * @param children 子依赖
 */
public record DependencyNode(Long assetId, boolean cycle, List<DependencyNode> children) {
}
