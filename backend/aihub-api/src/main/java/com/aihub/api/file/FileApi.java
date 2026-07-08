package com.aihub.api.file;

import com.aihub.api.dto.FileUploadResponse;
import com.aihub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * File Service 的内部接口契约（MinIO 的唯一出口）。
 *
 * <p>File Service 无状态：不持有数据表、不解析业务语义，对象路径（{@code objectKey}）由调用方
 * （Asset Service）决定并传入。
 *
 * <p>用 {@code application/octet-stream} 而非 multipart：调用方是服务不是浏览器，
 * 用 {@code byte[]} 传输不依赖 multipart 编码器，契约更确定（浏览器到 Gateway 那一跳仍是 multipart）。
 *
 * <p>下载返回裸字节流而不套 {@code Result}：二进制流无法包进统一 JSON 响应体，这是统一响应体的必要例外。
 */
@FeignClient(name = "aihub-file", contextId = "fileApi")
public interface FileApi {

    /**
     * 上传对象。
     *
     * @param objectKey   对象路径，调用方决定
     * @param contentType 内容类型，用于写入对象元数据
     * @param content     文件字节
     * @return 上传结果，含服务端计算的 SHA-256
     */
    @PostMapping(value = "/internal/v1/files", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    Result<FileUploadResponse> upload(@RequestParam("objectKey") String objectKey,
                                      @RequestParam(value = "contentType", required = false) String contentType,
                                      @RequestBody byte[] content);

    /**
     * 下载对象。
     *
     * @param objectKey 对象路径
     * @return 200 + 字节流；对象不存在时由下游转换为业务异常
     */
    @GetMapping(value = "/internal/v1/files", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<byte[]> download(@RequestParam("objectKey") String objectKey);

    /**
     * 删除对象。用于上传阶段失败时清理已落地的孤儿文件（docs/ARCHITECTURE.md §8.1）。
     *
     * @param objectKey 对象路径
     */
    @DeleteMapping("/internal/v1/files")
    Result<Void> delete(@RequestParam("objectKey") String objectKey);
}
