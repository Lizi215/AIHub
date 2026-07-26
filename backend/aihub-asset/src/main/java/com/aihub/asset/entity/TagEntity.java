package com.aihub.asset.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 标签字典（`tag`，docs/ARCHITECTURE.md §3：Tag 只服务 Asset，第一版不做社区内容标签）。
 */
@TableName("tag")
public class TagEntity extends BaseEntity {

    /** 标签名，唯一（含 deleted 的唯一索引保证删后可重建同名） */
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
