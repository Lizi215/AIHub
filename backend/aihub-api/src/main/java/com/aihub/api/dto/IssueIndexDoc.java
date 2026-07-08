package com.aihub.api.dto;

/**
 * 供 Search Service 建立索引的 Issue 数据（服务间 DTO），字段对应 aihub_issue_v1 的 mapping。
 *
 * <p>Issue 目前没有事件（docs/MESSAGING.md 未定义 Issue 事件），因此它的索引由对账任务补齐。
 */
public record IssueIndexDoc(
        String id,
        String assetId,
        String version,
        String title,
        String content,
        String status,
        String authorId,
        long opVersion,
        String createdAt,
        String updatedAt) {
}
