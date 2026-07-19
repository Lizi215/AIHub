package com.aihub.file.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.aihub.api.dto.FileUploadResponse;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.file.config.MinioProperties;
import com.aihub.file.service.FileStorageService;
import com.aihub.file.storage.FileValidator;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Set;

/**
 * MinIO 实现。File Service 只做字节流读写，不解析业务语义。
 */
@Service
public class MinioFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorageService.class);

    /** MinIO 在对象不存在时返回的 error code */
    private static final Set<String> NOT_FOUND_CODES = Set.of("NoSuchKey", "NoSuchObject", "NoSuchBucket");

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioFileStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public FileUploadResponse upload(String objectKey, String contentType, byte[] content) {
        // 校验放在落盘之前：任何一个环节失败都不应该留下半成品对象
        FileValidator.validate(objectKey, content, properties.getAllowedExtensions(), properties.getMaxSizeBytes());

        String checksum = DigestUtil.sha256Hex(content);
        String effectiveContentType = (contentType == null || contentType.isBlank())
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : contentType;

        try (InputStream in = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(in, content.length, -1)
                    .contentType(effectiveContentType)
                    .build());
        } catch (Exception e) {
            log.error("upload object failed, objectKey={}, size={}", objectKey, content.length, e);
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        log.info("object uploaded, objectKey={}, size={}, checksum={}", objectKey, content.length, checksum);
        return new FileUploadResponse(objectKey, content.length, effectiveContentType, checksum);
    }

    @Override
    public byte[] download(String objectKey) {
        try (GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(objectKey)
                .build())) {
            return response.readAllBytes();
        } catch (ErrorResponseException e) {
            if (NOT_FOUND_CODES.contains(e.errorResponse().code())) {
                throw new BizException(ErrorCode.FILE_NOT_FOUND, "object not found: " + objectKey);
            }
            log.error("download object failed, objectKey={}, code={}", objectKey, e.errorResponse().code(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        } catch (Exception e) {
            log.error("download object failed, objectKey={}", objectKey, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
            log.info("object removed, objectKey={}", objectKey);
        } catch (Exception e) {
            log.error("remove object failed, objectKey={}", objectKey, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }
}
