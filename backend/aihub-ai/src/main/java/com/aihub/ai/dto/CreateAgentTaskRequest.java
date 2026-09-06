package com.aihub.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建 Agent 任务请求（docs/AGENT.md §4）。
 *
 * <p>场景由调用方指定而不是让模型猜：场景决定允许调用哪些 Tool、是否必须确认，
 * 属于安全相关的输入。
 *
 * <p>{@code sessionId} 是前端生成的会话标识（UUID），仅用于把本任务与多轮会话历史关联
 * （Key = {@code agent:chat:{userId}:{sessionId}}，Redis 临时存储，TTL 2h）；可为空表示不关联。
 * Key 里强制带登录态 userId，跨用户隔离不依赖客户端自觉。
 */
public record CreateAgentTaskRequest(

        @NotBlank(message = "scene is required")
        @Size(max = 30, message = "scene must be at most 30 characters")
        String scene,

        @NotBlank(message = "request is required")
        @Size(max = 1000, message = "request must be at most 1000 characters")
        String request,

        @Size(max = 64, message = "sessionId must be at most 64 characters")
        @Pattern(regexp = "^[0-9a-fA-F-]{8,64}$", message = "sessionId must be a UUID-like string")
        String sessionId
) {
}
