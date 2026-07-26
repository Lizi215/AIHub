package com.aihub.asset.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 资源表映射（`asset`）。
 */
@TableName("asset")
public class AssetEntity extends BaseEntity {

    /** 归属用户，权限判定依赖（docs/ARCHITECTURE.md §9.2） */
    private Long ownerId;

    private String name;

    private String summary;

    private String category;

    /** 见 {@link com.aihub.asset.enums.AssetStatus} */
    private String status;

    private String latestVersion;

    /**
     * 资源维度的操作版本，每次发布递增。事件携带它用于消费端防乱序。
     * 见 {@link com.aihub.common.event.EventVersionPolicy}。
     */
    private Long opVersion;

    public Long getOpVersion() {
        return opVersion;
    }

    public void setOpVersion(Long opVersion) {
        this.opVersion = opVersion;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }
}
