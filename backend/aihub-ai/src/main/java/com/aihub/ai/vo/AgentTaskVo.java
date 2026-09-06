package com.aihub.ai.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 任务（对外）。
 *
 * @param status               PENDING / RUNNING / WAITING_CONFIRM / SUCCEEDED / FAILED / CANCELLED
 * @param awaitingConfirmation 是否为「等待用户确认」，前端据此展示计划与确认按钮
 * @param plan                 计划；未产出时为 null
 * @param result               汇总结果：planSummary / totalSteps / succeededSteps / failedSteps / steps
 * @param injectionDetected    上下文中是否命中 Prompt 注入特征；命中说明确实拦下过可疑内容
 * @param model                实际执行模式：{@code mock} 或模型名
 */
public record AgentTaskVo(
        Long id,
        String scene,
        String request,
        String status,
        boolean awaitingConfirmation,
        AgentPlanVo plan,
        List<AgentStepVo> steps,
        Object result,
        String error,
        int stepCount,
        int tokenUsed,
        boolean injectionDetected,
        String model,
        LocalDateTime createTime
) {
}
