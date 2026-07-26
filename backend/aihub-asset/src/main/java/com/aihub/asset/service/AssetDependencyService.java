package com.aihub.asset.service;

import com.aihub.asset.vo.AssetDependencyVo;
import com.aihub.asset.dependency.PackageDependencyParser;
import com.aihub.asset.vo.DependencyNodeVo;

import java.util.List;

/**
 * 资源依赖（docs/ARCHITECTURE.md §8.4）。
 *
 * <p>第一版只做基础依赖建模与展示，不实现复杂包管理器，但重复依赖、循环依赖、依赖不存在
 * 三种情况必须处理。
 */
public interface AssetDependencyService {

    /** 直接依赖列表。 */
    List<AssetDependencyVo> listDirect(Long assetId, Long currentUserId);

    /** 依赖树（展开传递依赖）。 */
    List<DependencyNodeVo> dependencyTree(Long assetId, Long currentUserId);

    /**
     * 按上传包里的 {@code aihub.yaml - requires} 声明同步资源依赖（上传时自动调用）。
     *
     * <p>语义是全量替换：以最新一次上传的版本为准，声明里没有的直接依赖会被移除；
     * 新包没有 aihub.yaml（或没写 requires）等价于空声明，此前的依赖会被清掉。
     * 解析不到的资源、自依赖、成环等不阻断流程，逐条作为说明返回给用户。
     *
     * @param declaredInPackage 包内是否声明了依赖，决定"移除"提示的措辞
     *
     * @return 面向用户的过程说明（已解析 N 条 / 未找到「x」已跳过 …），可能为空
     */
    List<String> syncFromManifest(Long assetId, List<PackageDependencyParser.Requirement> requirements,
                                  boolean declaredInPackage);
}
