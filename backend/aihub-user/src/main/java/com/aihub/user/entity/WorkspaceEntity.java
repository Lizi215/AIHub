package com.aihub.user.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Workspace 表映射（`workspace`）。
 *
 * <p>AI 只生成草案，经用户确认后由本表落库；条目在 {@link WorkspaceItemEntity}。
 */
@TableName("workspace")
public class WorkspaceEntity extends BaseEntity {

    private Long userId;

    private String name;

    /** 简介，AI 草案生成，可空 */
    private String description;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
