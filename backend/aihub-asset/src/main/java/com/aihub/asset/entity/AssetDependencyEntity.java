package com.aihub.asset.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 资源依赖表映射（`asset_dependency`）。
 */
@TableName("asset_dependency")
public class AssetDependencyEntity extends BaseEntity {

    /** 发起依赖的资源 */
    private Long assetId;

    /** 被依赖的资源 */
    private Long dependsOnAssetId;

    /** 版本约束，第一版只做建模不做解析（docs/ARCHITECTURE.md §8.4） */
    private String versionConstraint;

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public Long getDependsOnAssetId() {
        return dependsOnAssetId;
    }

    public void setDependsOnAssetId(Long dependsOnAssetId) {
        this.dependsOnAssetId = dependsOnAssetId;
    }

    public String getVersionConstraint() {
        return versionConstraint;
    }

    public void setVersionConstraint(String versionConstraint) {
        this.versionConstraint = versionConstraint;
    }
}
