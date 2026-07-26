package com.aihub.asset.enums;

/**
 * 资源状态机（docs/ARCHITECTURE.md §9.4）。
 *
 * <pre>
 * DRAFT ──发布──► PUBLISHED ──Admin 下架──► SUSPENDED
 * </pre>
 */
public enum AssetStatus {

    /** 草稿：仅 owner / Maintainer / Admin 可见，不建 ES 索引 */
    DRAFT,

    /** 已发布：公开可见 */
    PUBLISHED,

    /** 已下架：公开不可见 */
    SUSPENDED
}
