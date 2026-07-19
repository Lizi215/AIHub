package com.aihub.file.service;

import com.aihub.api.dto.FileUploadResponse;

/**
 * 对象存储读写能力。无状态，不持有任何数据表。
 */
public interface FileStorageService {

    /**
     * 上传对象。
     *
     * @param objectKey   对象路径（由调用方决定）
     * @param contentType 内容类型
     * @param content     文件字节
     * @return 上传结果，含服务端计算的 SHA-256
     */
    FileUploadResponse upload(String objectKey, String contentType, byte[] content);

    /**
     * 读取对象字节。
     *
     * @param objectKey 对象路径
     */
    byte[] download(String objectKey);

    /**
     * 删除对象。幂等：对象不存在时视为成功。
     */
    void delete(String objectKey);
}
