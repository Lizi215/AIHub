package com.aihub.asset.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.aihub.common.jackson.NumericLongSerializer;

import java.util.List;

/**
 * 版本信息。
 *
 * @param id           版本 id（雪花 ID，保持字符串）
 * @param version      版本号
 * @param changelog    更新日志
 * @param readme       README 正文
 * @param publishStatus 版本发布状态
 * @param checksum     文件 SHA-256，供调用方校验字节一致性
 * @param size         字节数。计量值而非 ID，显式覆盖全局 Long→String 规则
 * @param contentType  内容类型
 * @param dependencyNotes 依赖解析说明；仅上传响应填充，列表接口为 null
 */
public record AssetVersionVo(
        Long id,
        String version,
        String changelog,
        String readme,
        String publishStatus,
        String checksum,
        @JsonSerialize(using = NumericLongSerializer.class) Long size,
        String contentType,
        List<String> dependencyNotes
) {
    /** 上传响应里带上依赖解析说明；其他场景（版本列表）保持 null。 */
    public AssetVersionVo withDependencyNotes(List<String> notes) {
        return new AssetVersionVo(id, version, changelog, readme, publishStatus,
                checksum, size, contentType, notes);
    }
}
