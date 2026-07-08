package com.aihub.api.event;

/**
 * 下载完成事件。
 *
 * <p>由 File Service 侧在文件真正下发后产生，消费方有两个（docs/ARCHITECTURE.md §8.2）：
 * DownloadConsumer → User Service 写 download_record；
 * HotRankConsumer → Asset Service 计下载数并更新 Redis 热门榜。
 *
 * @param eventId      事件唯一标识，消费端据此幂等
 * @param eventVersion 事件结构版本
 * @param userId       下载者
 * @param assetId      资源 id
 * @param version      版本号（同一用户对同一 asset + version 在窗口内只计一次）
 * @param occurredAt   发生时间（epoch millis）
 */
public record DownloadCompletedEvent(
        String eventId,
        int eventVersion,
        long userId,
        long assetId,
        String version,
        long occurredAt
) {
}
