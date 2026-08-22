package com.aihub.community.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/** 帖子评论。 */
@TableName("post_comment")
public class PostCommentEntity extends BaseEntity {

    private Long postId;

    private Long authorId;

    /** 纯文本 / Markdown，禁止存 HTML */
    private String content;

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
