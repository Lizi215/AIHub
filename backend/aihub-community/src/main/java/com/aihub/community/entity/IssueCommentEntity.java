package com.aihub.community.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/** Issue 评论（资源讨论的回复）。 */
@TableName("issue_comment")
public class IssueCommentEntity extends BaseEntity {

    private Long issueId;

    private Long authorId;

    /** 纯文本 / Markdown，禁止存 HTML */
    private String content;

    public Long getIssueId() {
        return issueId;
    }

    public void setIssueId(Long issueId) {
        this.issueId = issueId;
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
