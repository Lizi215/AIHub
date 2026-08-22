package com.aihub.community.vo;

import java.time.LocalDateTime;

/**
 * Issue 视图。
 *
 * <p>{@code authorName} 来自 User Service，取不到时为 null，由前端兜底。
 */
public record IssueVo(
        Long id,
        Long authorId,
        Long assetId,
        String version,
        String title,
        String content,
        String status,
        LocalDateTime createTime,
        String authorName
) {
}
