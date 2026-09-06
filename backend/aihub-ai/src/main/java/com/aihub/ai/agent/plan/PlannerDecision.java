package com.aihub.ai.agent.plan;

import java.util.List;

/**
 * 规划器的一次决策。
 *
 * <p>两种形态必须二选一：{@code toolCalls} 非空则先执行这些只读调用，拿到观察结果后
 * 再决策一次；{@code plan} 非空则信息够了，产出计划。两者同时为空视为规划失败，
 * 由 Orchestrator 报错，而不是当作完成了。
 *
 * @param toolCalls 本轮要执行的只读工具调用（可为空列表）
 * @param plan      最终计划；为 null 表示还需要继续收集信息
 * @param tokenUsed 本次决策消耗的 Token（真实模型取实际用量，mock 取估算值）
 */
public record PlannerDecision(List<AgentPlanStep> toolCalls, AgentPlan plan, int tokenUsed) {

    public static PlannerDecision gather(List<AgentPlanStep> toolCalls, int tokenUsed) {
        return new PlannerDecision(toolCalls == null ? List.of() : List.copyOf(toolCalls), null, tokenUsed);
    }

    public static PlannerDecision finish(AgentPlan plan, int tokenUsed) {
        return new PlannerDecision(List.of(), plan, tokenUsed);
    }
}
