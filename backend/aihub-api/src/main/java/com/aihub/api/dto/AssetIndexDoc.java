package com.aihub.api.dto;

import java.util.List;

/**
 * 供 Search Service 建立索引的资产数据（服务间 DTO）。
 *
 * <p>字段与 `docs/SEARCH.md` §2 的 Asset 索引约定一一对应：Search 侧拿到它几乎可以原样写入 ES，
 * 不需要（也不允许）回查 Asset 的表。
 *
 * <p>时间用 ISO-8601 字符串而不是 LocalDateTime：ES 客户端用自己的 Jackson 映射器，
 * 不认 Spring 的 JavaTime 配置，直接喂 ISO 字符串能避免序列化成时间戳数组这类坑。
 *
 * @param downloadCount 累计下载量，由天级任务从 Redis 回写（docs/SEARCH.md §3），当前恒为 0
 * @param latestVersion 最新版本号。事件链路取自事件体，对账链路取自 asset 表；Agent 的
 *                      search_assets 契约里有它，没有就得再调一次详情才知道版本
 */
public record AssetIndexDoc(
        String id,
        String title,
        String description,
        String readme,
        List<String> tags,
        String category,
        String authorId,
        String status,
        long opVersion,
        long downloadCount,
        String createdAt,
        String updatedAt,
        String latestVersion) {
}
