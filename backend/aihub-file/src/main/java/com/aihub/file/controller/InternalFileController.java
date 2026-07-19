package com.aihub.file.controller;

import com.aihub.api.dto.FileUploadResponse;
import com.aihub.api.file.FileApi;
import com.aihub.common.result.Result;
import com.aihub.file.service.FileStorageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间文件接口，实现 {@link FileApi} 契约。
 *
 * <p>路径 {@code /internal/v1/**} 不在 Gateway 路由表中，因此不会暴露到公网（docs/SECURITY.md §3）。
 *
 * <p>实现方法上重复声明映射注解：参数注解是否从接口继承在 Spring MVC 里容易出现意外，
 * 显式声明可避免「路径对了但参数绑不上」的隐性问题；两侧路径漂移时端到端调用会立刻失败。
 */
@RestController
public class InternalFileController implements FileApi {

    private final FileStorageService fileStorageService;

    public InternalFileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Override
    @PostMapping(value = "/internal/v1/files", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Result<FileUploadResponse> upload(@RequestParam("objectKey") String objectKey,
                                             @RequestParam(value = "contentType", required = false) String contentType,
                                             @RequestBody byte[] content) {
        return Result.ok(fileStorageService.upload(objectKey, contentType, content));
    }

    @Override
    @GetMapping(value = "/internal/v1/files", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> download(@RequestParam("objectKey") String objectKey) {
        byte[] content = fileStorageService.download(objectKey);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(content.length)
                .body(content);
    }

    @Override
    @DeleteMapping("/internal/v1/files")
    public Result<Void> delete(@RequestParam("objectKey") String objectKey) {
        fileStorageService.delete(objectKey);
        return Result.ok();
    }
}
