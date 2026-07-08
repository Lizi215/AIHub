package com.aihub.api.dto;

/**
 * 资源检索结果项（服务间 DTO，供 Agent 的 Search Tool 使用）。
 *
 * <p>字段与 {@code GET /api/v1/assets} 的列表项保持一致，避免出现两套语义不同的「资源列表」；
 * 不复用 Asset 模块的 VO 是因为 {@code aihub-api} 不能依赖任何服务的实现模块（docs/BACKEND.md §1）。
 *
 * <p>只包含已发布资源：内部查询以匿名视角执行，草稿不进 Agent 上下文。
 */
public record AssetQueryItem(
        Long id,
        Long ownerId,
        String name,
        String summary,
        String category,
        String status,
        String latestVersion) {
}
