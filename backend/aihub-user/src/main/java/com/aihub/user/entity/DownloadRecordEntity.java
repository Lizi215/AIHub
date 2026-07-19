package com.aihub.user.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 下载记录表映射（`download_record`）。
 */
@TableName("download_record")
public class DownloadRecordEntity extends BaseEntity {

    private Long userId;

    private Long assetId;

    private String version;

    /** 下载发生时间，取事件时间而非落库时间，避免消息延迟导致时间失真 */
    private LocalDateTime occurredAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

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

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}
