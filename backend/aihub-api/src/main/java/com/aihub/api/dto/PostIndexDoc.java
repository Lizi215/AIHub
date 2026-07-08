package com.aihub.api.dto;

/**
 * 供 Search Service 建立索引的帖子数据（服务间 DTO），字段对应 aihub_post_v1 的 mapping。
 *
 * <p>不含 likeCount：点赞目前不产生事件（docs/MESSAGING.md 只定义 3 个事件），
 * 放进索引只会展示过期数据。
 *
 * @param updatedAt ISO-8601 字符串（ES 的 date 字段直接吃 ISO，避免时区/格式歧义）
 */
public record PostIndexDoc(
        String id,
        String type,
        String title,
        String content,
        String authorId,
        String assetId,
        long opVersion,
        String createdAt,
        String updatedAt) {
}
