package com.aihub.api.dto;

import java.util.List;

/**
 * 标签写入指令（AI 服务 → Asset 服务）。
 *
 * <p>ownerId 必须由调用方从任务行取（{@code agent_task.user_id}），不能来自模型参数，
 * 否则模型可以给任意用户的资源打标签。Asset 侧按 ownerId 做归属校验，越权直接拒绝。
 *
 * @param ownerId 资源归属者（服务端事实，非模型可控）
 * @param tags    标签名列表（Asset 侧会清洗：去空白、去重、限长限数）
 * @param mode    {@code APPEND}（并入已有）/ {@code REPLACE}（整组替换）
 */
public record TagUpdateCommand(Long ownerId, List<String> tags, String mode) {
}
