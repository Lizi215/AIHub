package com.aihub.asset.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 资源版本表映射（`asset_version`）。
 */
@TableName("asset_version")
public class AssetVersionEntity extends BaseEntity {

    private Long assetId;

    private String version;

    private String changelog;

    /** README 属描述性元数据，存 MySQL 而非对象存储（docs/DATA.md §2） */
    private String readme;

    /** 见 {@link com.aihub.asset.enums.VersionPublishStatus} */
    private String publishStatus;

    private LocalDateTime publishedAt;

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public String getReadme() {
        return readme;
    }

    public void setReadme(String readme) {
        this.readme = readme;
    }

    public String getPublishStatus() {
        return publishStatus;
    }

    public void setPublishStatus(String publishStatus) {
        this.publishStatus = publishStatus;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
