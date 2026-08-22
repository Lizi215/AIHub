package com.aihub.community.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Issue 表映射（`issue`）。独立实体，不塞进 `post`。
 */
@TableName("issue")
public class IssueEntity extends BaseEntity {

    private Long authorId;

    /** 关联资源，必填——Issue 是围绕资源展开的 */
    private Long assetId;

    private String version;

    private String title;

    private String content;

    /** 见 {@link com.aihub.community.enums.IssueStatus} */
    private String status;

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
