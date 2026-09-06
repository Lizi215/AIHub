package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.agent.plan.AgentPlanningState;
import com.aihub.ai.agent.plan.PlannerDecision;

/**
 * 规划器：决定「下一步调用哪些只读 Tool」或「信息够了，产出计划」。
 *
 * <p>循环由 Orchestrator 驱动，而不是把 Tool 交给模型框架自行调用，因为
 * docs/AGENT.md §7 要求步数上限、超时、循环检测、Token 限额全部生效。交给框架内部，
 * 这些约束就只能靠提示词和框架默认上限，既不可测也不可解释。所以这里把决策与执行拆开：
 * 规划器只产出调用意图，执行一律经 {@link AgentToolInvoker}（守卫的唯一入口）。
 *
 * <p>两个实现共用同一份 Tool 定义（Spring AI {@code @Tool} 注解的 Bean 方法）：
 * {@link MockAgentPlanner} 是无 Key 时的脚本化实现，保证没有 Key 的机器也能跑通链路；
 * {@link SpringAiAgentPlanner} 在有 Key 时由真实模型决策，Tool 清单与 JSON Schema
 * 取自 Spring AI 的 {@code ToolDefinition}。
 */
public interface AgentPlanner {

    /** 执行模式标识，写入 {@code agent_task.model}，用于区分"mock 演示"与"真实模型"。 */
    String mode();

    /**
     * 给出下一步决策。
     *
     * @param state 当前状态（场景、用户请求、已有观察结果、已消耗步数）
     */
    PlannerDecision next(AgentPlanningState state);
}
