package com.aihub.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * File Service 的对象存储配置。
 *
 * <p>密钥来自环境变量 / Apollo，禁止硬编码（docs/SECURITY.md §1）。
 */
@ConfigurationProperties(prefix = "aihub.file.minio")
public class MinioProperties {

    private String endpoint = "http://127.0.0.1:9000";

    private String accessKey;

    private String secretKey;

    /** 存放对象桶名。启动时会确保其存在。 */
    private String bucket = "aihub";

    /** 单文件大小上限，默认 50MB。上传链路采用中转模式，字节会驻留堆内存，因此必须设上限。 */
    private long maxSizeBytes = 50L * 1024 * 1024;

    /** 允许的扩展名白名单。仅信任扩展名是不够的，还会校验文件魔数。 */
    private List<String> allowedExtensions = List.of("zip");

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }
}
