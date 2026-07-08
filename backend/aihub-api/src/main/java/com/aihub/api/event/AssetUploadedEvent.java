package com.aihub.api.event;

import java.util.List;

/**
 * 资源发布事件（含新增版本）。
 *
 * <p>消费方是 Search Service：把资源建成 / 更新到 ES 索引。
 * `docs/DATA.md` §2 指出 README 属描述性元数据存 MySQL，但建索引时需要它，
 * 因此事件体里带上 README，避免消费端再回查 Asset Service（那会造成反向依赖）。
 *
 * @param eventId      事件唯一标识，消费端据此幂等
 * @param eventVersion 事件结构版本
 * @param opVersion    该资源维度的操作版本，单调递增；消费端据此防乱序
 * @param assetId      资源 id
 * @param ownerId      归属用户
 * @param name         资源名
 * @param summary      简介
 * @param category     分类
 * @param status       资源状态（用于判断是否应进索引：DRAFT / SUSPENDED 不进）
 * @param version      版本号
 * @param readme       README 正文
 * @param tags         标签名列表（标签挂载/整组替换也会触发本事件，消费端写入 ES tags 字段；
 *                     旧消息可能缺失该字段，消费端按 null 处理）
 * @param occurredAt   发生时间（epoch millis）
 */
public record AssetUploadedEvent(
        String eventId,
        int eventVersion,
        long opVersion,
        long assetId,
        long ownerId,
        String name,
        String summary,
        String category,
        String status,
        String version,
        String readme,
        List<String> tags,
        long occurredAt
) {
}
