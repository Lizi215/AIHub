package com.aihub.ai.vo;

import java.util.List;
import java.util.Map;

/**
 * 计划中的一步（对外）。
 *
 * <p>{@code requiresConfirmation} 由服务端按 Tool 性质判定，前端据此渲染"需要确认"的标记。
 */
public record AgentPlanStepVo(
        String tool,
        String intent,
        boolean requiresConfirmation,
        Map<String, Object> args
) {
}
