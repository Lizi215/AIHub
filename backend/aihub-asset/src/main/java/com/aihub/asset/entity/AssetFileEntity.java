package com.aihub.asset.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 文件元数据表映射（`asset_file`）。只存元数据，绝不存文件内容。
 */
@TableName("asset_file")
public class AssetFileEntity extends BaseEntity {

    private Long assetId;

    private Long versionId;

    /** 对象存储路径，由 Asset Service 决定，File Service 不解析语义 */
    private String objectKey;

    private Long size;

    private String contentType;

    /** SHA-256 十六进制摘要，用于校验"下载回来与上传一致" */
    private String checksum;

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }
}
