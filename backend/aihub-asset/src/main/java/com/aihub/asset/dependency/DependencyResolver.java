package com.aihub.asset.dependency;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 依赖图算法（docs/ARCHITECTURE.md §8.4）。
 *
 * <p>无状态纯逻辑，图数据由 {@link DependencyGraphSource} 提供，可脱离数据库单测。
 * 重复依赖与循环依赖（含自依赖）在这里判定；依赖是否存在需要查库，由 service 负责。
 */
public class DependencyResolver {

    private final DependencyGraphSource source;

    /** 依赖链最大深度，防止异常数据导致遍历失控 */
    private final int maxDepth;

    public DependencyResolver(DependencyGraphSource source, int maxDepth) {
        this.source = source;
        this.maxDepth = maxDepth;
    }

    /**
     * 校验能否把 {@code assetId → dependencyId} 这条依赖加进去。
     *
     * @throws BizException 重复依赖抛 {@link ErrorCode#ASSET_DEPENDENCY_ALREADY_EXISTS}，
     *                      自依赖或成环抛 {@link ErrorCode#ASSET_DEPENDENCY_CYCLE}
     */
    public void assertCanDependOn(Long assetId, Long dependencyId) {
        if (assetId == null || dependencyId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (assetId.equals(dependencyId)) {
            // 自依赖本质上就是长度为 1 的环，直接按成环处理
            throw new BizException(ErrorCode.ASSET_DEPENDENCY_CYCLE, "an asset cannot depend on itself");
        }
        List<Long> existing = source.directDependencies(assetId);
        if (existing != null && existing.contains(dependencyId)) {
            throw new BizException(ErrorCode.ASSET_DEPENDENCY_ALREADY_EXISTS);
        }
        if (reaches(dependencyId, assetId)) {
            // dependency 已经（直接或间接）依赖 asset，再加这条边就成环
            throw new BizException(ErrorCode.ASSET_DEPENDENCY_CYCLE,
                    "adding this dependency would create a cycle: " + assetId + " -> " + dependencyId);
        }
    }

    /**
     * 从 {@code from} 出发，沿直接依赖向下，判断是否能到达 {@code target}。
     *
     * <p>用迭代 + visited 集合而非递归：数据里若已存在环，递归会栈溢出。
     */
    private boolean reaches(Long from, Long target) {
        Set<Long> visited = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(from);
        int depth = 0;

        while (!stack.isEmpty()) {
            if (++depth > maxDepth) {
                // 超过深度上限说明图异常，按成环拒绝而不是放行
                throw new BizException(ErrorCode.ASSET_DEPENDENCY_CYCLE, "dependency depth exceeds limit");
            }
            Long current = stack.pop();
            if (current.equals(target)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            List<Long> next = source.directDependencies(current);
            if (next != null) {
                next.forEach(stack::push);
            }
        }
        return false;
    }

    /**
     * 构建依赖树（含传递依赖）。
     *
     * <p>用「当前路径」而非全局 visited 剪枝：菱形依赖（A→B→D、A→C→D）合法，两处都要展示；
     * 只有指回祖先的边才是环，标记后不再展开。
     */
    public List<DependencyNode> buildTree(Long rootId) {
        return expand(rootId, new HashSet<>(Set.of(rootId)), 0);
    }

    private List<DependencyNode> expand(Long assetId, Set<Long> path, int depth) {
        List<DependencyNode> nodes = new ArrayList<>();
        if (depth >= maxDepth) {
            return nodes;
        }
        List<Long> children = source.directDependencies(assetId);
        if (children == null) {
            return nodes;
        }
        for (Long child : children) {
            if (path.contains(child)) {
                nodes.add(new DependencyNode(child, true, List.of()));
                continue;
            }
            path.add(child);
            nodes.add(new DependencyNode(child, false, expand(child, path, depth + 1)));
            // 回溯：同一层的兄弟分支互不影响，否则菱形依赖会被误剪
            path.remove(child);
        }
        return nodes;
    }
}
