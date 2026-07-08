package com.aihub.api.event;

/**
 * 帖子创建事件（docs/MESSAGING.md §1：`PostCreatedEvent → IndexConsumer → Search Service`）。
 *
 * <p>为什么事件体里带上 title/content：消费端（Search）需要它们建索引，
 * 而 Search 不持有业务表、也不能回查 Community 的表，
 * 与 {@link AssetUploadedEvent} 带 README 是同一个理由。
 *
 * @param eventId      事件唯一标识，消费端据此幂等
 * @param eventVersion 事件结构版本
 * @param opVersion    业务对象版本（帖子目前只有创建这一个事件，取 1）
 * @param postId       帖子 id
 * @param authorId     作者
 * @param type         EXPERIENCE / GUIDE / QUESTION
 * @param title        标题
 * @param content      正文（Markdown 纯文本）
 * @param assetId      关联资源 id，可为空
 * @param occurredAt   发生时间（epoch millis）
 */
public record PostCreatedEvent(
        String eventId,
        int eventVersion,
        long opVersion,
        long postId,
        long authorId,
        String type,
        String title,
        String content,
        Long assetId,
        long occurredAt
) {
}
