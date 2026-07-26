package com.aihub.asset.dependency;

import java.util.List;

/**
 * 依赖图的取数口。
 *
 * <p>把「图从哪来」与「图怎么算」分开：算法（环检测、依赖树）是无状态纯逻辑，
 * 可脱离数据库直接单测。
 */
@FunctionalInterface
public interface DependencyGraphSource {

    /**
     * 返回某资源的**直接**依赖 id 列表。
     */
    List<Long> directDependencies(Long assetId);
}
