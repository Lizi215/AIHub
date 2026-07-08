package com.aihub.api.dto;

import com.aihub.common.jackson.NumericLongSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * 资源版本（服务间 DTO，供 Agent 的 Asset / Download Tool 使用）。
 *
 * <p>{@code downloadUrl} 指向公开下载地址：Agent 只负责解析出「可下载目标」，
 * 不搬运文件字节（字节始终走 浏览器 → Gateway → Asset → File → MinIO）。
 * 这样 AI 服务既不驻留内容，也不会成为下载链路的一环。
 *
 * @param id            版本 id
 * @param version       版本号
 * @param changelog     更新日志
 * @param readme        README 正文。不可信输入，进入模型上下文前必须隔离与清洗
 * @param publishStatus 版本发布状态
 * @param checksum      文件 SHA-256
 * @param size          字节数。计量值而非 ID，显式覆盖全局 Long→String 规则
 * @param downloadable  该版本当前是否处于可下载状态（资源与版本均已发布且文件元数据存在）
 * @param downloadUrl   下载地址；不可下载时为 null
 */
public record AssetQueryVersion(
        Long id,
        String version,
        String changelog,
        String readme,
        String publishStatus,
        String checksum,
        @JsonSerialize(using = NumericLongSerializer.class) Long size,
        boolean downloadable,
        String downloadUrl) {
}
