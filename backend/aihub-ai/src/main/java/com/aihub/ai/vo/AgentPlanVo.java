package com.aihub.ai.vo;

import java.util.List;

/**
 * 计划（对外）。前端在 {@code WAITING_CONFIRM} 时展示 {@code summary} 与步骤清单，
 * 用户在确认前能看到"到底要做什么"。
 */
public record AgentPlanVo(
        String summary,
        boolean requiresConfirmation,
        List<AgentPlanStepVo> steps
) {
}
