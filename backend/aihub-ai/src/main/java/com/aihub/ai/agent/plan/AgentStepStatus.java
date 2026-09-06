package com.aihub.ai.agent.plan;

/**
 * 步骤执行结果。
 */
public enum AgentStepStatus {

    /** 已执行且成功 */
    SUCCEEDED,
    /** 已执行但失败，{@code message} 必须写明原因 */
    FAILED
}
