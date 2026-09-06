package com.aihub.ai.vo;

import java.util.List;

/**
 * 步骤执行记录（对外）。
 *
 * <p>不返回参数与结构化结果：{@code agent_task.steps} 的原始内容是排障用的执行日志
 * （docs/SECURITY.md §4），{@code result} 里已按「已完成 / 失败 / 失败原因」汇总，
 * 前端展示用后者即可。
 */
public record AgentStepVo(
        String tool,
        String intent,
        String status,
        String message,
        List<String> warnings,
        long durationMs,
        boolean confirmedAt
) {
}
