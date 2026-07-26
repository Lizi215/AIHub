package com.aihub.asset.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 资源-标签关联（`asset_tag`）。
 */
@TableName("asset_tag")
public class AssetTagEntity extends BaseEntity {

    private Long assetId;

    private Long tagId;

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public Long getTagId() {
        return tagId;
    }

    public void setTagId(Long tagId) {
        this.tagId = tagId;
    }
}
