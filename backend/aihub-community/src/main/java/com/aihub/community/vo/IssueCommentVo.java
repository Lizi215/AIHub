package com.aihub.community.vo;

import java.time.LocalDateTime;

/**
 * Issue 评论视图。
 *
 * <p>{@code authorName} 来自 User Service，取不到时为 null，由前端兜底。
 */
public record IssueCommentVo(
        Long id,
        Long issueId,
        Long authorId,
        String content,
        LocalDateTime createTime,
        String authorName
) {
}
