package com.aihub.api.dto;

import java.util.List;

/**
 * 资源详情（服务间 DTO，供 Agent 的 Asset / Download Tool 使用）。
 *
 * <p>把「资源 + 最新版本 README + 全部版本」一次返回，让 Agent 一次调用就能拿到制定下载计划
 * 所需的全部信息，多一次跨服务往返就多一次超时与失败的机会。
 *
 * @param readme   最新版本 README。不可信输入（用户上传的文本），进入模型上下文前必须隔离与清洗
 * @param versions 全部版本（按创建时间倒序）
 */
public record AssetQueryDetail(
        Long id,
        Long ownerId,
        String name,
        String summary,
        String category,
        String status,
        String latestVersion,
        String readme,
        List<AssetQueryVersion> versions) {
}
