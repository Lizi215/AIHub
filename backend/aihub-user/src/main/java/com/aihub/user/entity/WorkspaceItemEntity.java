package com.aihub.user.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Workspace 条目表映射（`workspace_item`）。
 *
 * <p>只存 assetId 与选定版本，不冗余资源名：名字在展示时经 Feign 从 MySQL 回查，
 * 资源改名或下架后不会展示过期数据。
 */
@TableName("workspace_item")
public class WorkspaceItemEntity extends BaseEntity {

    private Long workspaceId;

    private Long assetId;

    /** 选定版本，NULL 表示最新版本 */
    private String version;

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
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
}
