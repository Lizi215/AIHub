package com.aihub.community.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 帖子表映射（`post`）。三类帖子共用一张表，用 {@code type} 区分。
 */
@TableName("post")
public class PostEntity extends BaseEntity {

    private Long authorId;

    /** 见 {@link com.aihub.community.enums.PostType} */
    private String type;

    private String title;

    /** 按纯文本 / Markdown 存储，**禁止存储 HTML**（docs/SECURITY.md §3） */
    private String content;

    private Long assetId;

    /** 点赞数。MySQL 为唯一事实来源，Redis 集合只是去重快路径 */
    private Long likeCount;

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public Long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Long likeCount) {
        this.likeCount = likeCount;
    }
}
