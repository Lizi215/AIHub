package com.aihub.ai.agent.plan;

import java.util.Map;

/**
 * 计划中的一步。
 *
 * @param tool                 Tool 名，必须在 {@code AgentToolCatalog} 中注册
 * @param args                 参数，来自模型或脚本，不可信任；执行时统一走反序列化 + 校验
 * @param intent               这一步要做什么（给人看，进汇总结果）
 * @param requiresConfirmation 是否属于需确认操作。由 Orchestrator 按 Tool 性质覆写，
 *                             不采信模型自报的值
 */
public record AgentPlanStep(String tool, Map<String, Object> args, String intent, boolean requiresConfirmation) {

    public AgentPlanStep withConfirmation(boolean required) {
        return new AgentPlanStep(tool, args, intent, required);
    }
}
