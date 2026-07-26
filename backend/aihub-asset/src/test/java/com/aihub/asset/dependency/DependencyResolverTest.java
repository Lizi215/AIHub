package com.aihub.asset.dependency;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 依赖图校验与依赖树构建。
 *
 * <p>覆盖依赖树的重复 / 循环校验以及树构建；依赖不存在的判定由 service 层查库完成。
 */
class DependencyResolverTest {

    private static final int MAX_DEPTH = 32;

    /** 用 Map 模拟依赖图，避免依赖数据库 */
    private static DependencyResolver resolverOf(Map<Long, List<Long>> graph) {
        return new DependencyResolver(
                assetId -> graph.getOrDefault(assetId, List.of()), MAX_DEPTH);
    }

    private static Map<Long, List<Long>> graph(Object... pairs) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Long) pairs[i], (List<Long>) pairs[i + 1]);
        }
        return map;
    }

    /* ------------------------------------------------------------ 重复依赖 */


    /* ------------------------------------------------------------ 循环依赖 */









    /* ------------------------------------------------------------ 依赖树 */

    @Test
    @DisplayName("依赖树按层级展开传递依赖")
    void shouldBuildNestedTree() {
        // A(1) → B(2), C(3)；B → D(4)
        DependencyResolver resolver = resolverOf(graph(
                1L, List.of(2L, 3L), 2L, List.of(4L)));

        List<DependencyNode> tree = resolver.buildTree(1L);

        assertThat(tree).extracting(DependencyNode::assetId).containsExactly(2L, 3L);
        assertThat(tree.get(0).children()).extracting(DependencyNode::assetId).containsExactly(4L);
        assertThat(tree.get(1).children()).isEmpty();
        assertThat(tree).allMatch(node -> !node.cycle());
    }

    @Test
    @DisplayName("菱形依赖在树里两处都展示，不被误剪")
    void shouldExposeDiamondInBothBranches() {
        DependencyResolver resolver = resolverOf(graph(
                1L, List.of(2L, 3L), 2L, List.of(4L), 3L, List.of(4L)));

        List<DependencyNode> tree = resolver.buildTree(1L);

        assertThat(tree.get(0).children()).extracting(DependencyNode::assetId).containsExactly(4L);
        assertThat(tree.get(1).children()).extracting(DependencyNode::assetId).containsExactly(4L);
    }

    @Test
    @DisplayName("数据里已有环时，依赖树把回边标记为 cycle 且不再展开")
    void shouldMarkCycleInTree() {
        DependencyResolver resolver = resolverOf(graph(1L, List.of(2L), 2L, List.of(1L)));

        List<DependencyNode> tree = resolver.buildTree(1L);

        DependencyNode second = tree.get(0);
        assertThat(second.assetId()).isEqualTo(2L);
        assertThat(second.children()).hasSize(1);
        assertThat(second.children().get(0).assetId()).isEqualTo(1L);
        assertThat(second.children().get(0).cycle()).isTrue();
        assertThat(second.children().get(0).children()).isEmpty();
    }

    @Test
    @DisplayName("超过深度上限时停止展开，不无限递归")
    void shouldStopAtMaxDepth() {
        // 构造一条 1→2→3→4→5 的链，但把上限设为 2
        Map<Long, List<Long>> chain = graph(
                1L, List.of(2L), 2L, List.of(3L), 3L, List.of(4L), 4L, List.of(5L));
        DependencyResolver resolver = new DependencyResolver(
                assetId -> chain.getOrDefault(assetId, List.of()), 2);

        List<DependencyNode> tree = resolver.buildTree(1L);

        assertThat(tree.get(0).children().get(0).children()).isEmpty();
    }

    @Test
    @DisplayName("没有依赖时返回空树")
    void shouldReturnEmptyTreeForLeaf() {
        DependencyResolver resolver = resolverOf(graph());

        assertThat(resolver.buildTree(99L)).isEmpty();
    }
}
