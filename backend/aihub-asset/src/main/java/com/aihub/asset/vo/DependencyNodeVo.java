package com.aihub.asset.vo;

import java.util.List;

/**
 * 依赖树节点（对外）。
 *
 * @param assetId  资源 id
 * @param name     资源名
 * @param cycle    是否指向祖先节点（数据异常时才会为 true）
 * @param children 子依赖
 */
public record DependencyNodeVo(Long assetId, String name, boolean cycle, List<DependencyNodeVo> children) {
}
