package com.aihub.ai.agent.plan;

import java.util.List;

/**
 * 执行计划。
 *
 * <p>计划是给人确认的，{@code summary} 要是一段读得懂的话，而不是步骤的机械拼接，
 * docs/AGENT.md §5 的确认弹窗展示的就是它。
 *
 * @param summary 一句话说明这次要做什么
 * @param steps   步骤列表，按执行顺序
 */
public record AgentPlan(String summary, List<AgentPlanStep> steps) {

    /** 是否包含需用户确认的步骤（决定任务是否进入 WAITING_CONFIRM）。 */
    public boolean requiresConfirmation() {
        return steps.stream().anyMatch(AgentPlanStep::requiresConfirmation);
    }
}
