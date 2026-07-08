package com.aihub.api.dto;

import java.time.LocalDateTime;

/**
 * Issue 检索结果项（服务间 DTO，供 Agent 的 Community Tool 使用）。
 *
 * <p>{@code content} 是用户输入，不可信：进入模型上下文前必须隔离与清洗
 * （docs/AGENT.md §7 的 Prompt 注入防护）。
 */
public record IssueQueryItem(
        Long id,
        Long assetId,
        String version,
        String title,
        String content,
        String status,
        LocalDateTime createTime) {
}
