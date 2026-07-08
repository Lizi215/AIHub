package com.aihub.api.dto;

import java.time.LocalDateTime;

/**
 * 帖子检索结果项（服务间 DTO，供 Agent 的 Community Tool 使用）。
 *
 * <p>{@code content} 是用户输入，不可信：进入模型上下文前必须隔离与清洗。
 */
public record PostQueryItem(
        Long id,
        Long authorId,
        String type,
        String title,
        String content,
        Long assetId,
        Long likeCount,
        LocalDateTime createTime) {
}
