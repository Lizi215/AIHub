package com.aihub.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.aihub.common.jackson.NumericLongSerializer;

/**
 * 文件上传结果（服务间 DTO）。
 *
 * @param objectKey   对象存储路径，由调用方提供并原样回传
 * @param size        字节数。字节数是计量值而非 ID，显式覆盖全局 Long→String 规则保持数字类型
 * @param contentType 内容类型
 * @param checksum    服务端计算的 SHA-256 十六进制摘要，供调用方与本地计算值比对
 */
public record FileUploadResponse(
        String objectKey,
        @JsonSerialize(using = NumericLongSerializer.class) long size,
        String contentType,
        String checksum) {
}
