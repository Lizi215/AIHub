package com.aihub.ai.agent.plan;

import java.util.List;

/**
 * 规划阶段的当前状态，交给 {@link com.aihub.ai.agent.orchestrator.AgentPlanner} 决定下一步。
 *
 * <p>上一步的观察结果是模型继续推理的唯一依据：观察 → 决策 → 调用 → 再观察。
 *
 * @param scene          场景
 * @param userRequest    用户原始请求
 * @param observations   已执行的只读步骤及其结果
 * @param stepCount      已消耗步数
 * @param history        本会话最近几轮对话（时间正序，有界裁剪，docs/AGENT.md §6）；无会话时为空
 */
public record AgentPlanningState(
        com.aihub.ai.enums.AgentScene scene,
        String userRequest,
        List<AgentStepRecord> observations,
        int stepCount,
        List<com.aihub.ai.agent.chat.HistoryTurn> history) {

    public AgentPlanningState {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public boolean hasObservations() {
        return !observations.isEmpty();
    }
}
