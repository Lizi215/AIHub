package com.aihub.ai.enums;

/**
 * Agent 任务状态（docs/AGENT.md §4）。
 *
 * <pre>
 * PENDING → RUNNING → SUCCEEDED
 *                  ├→ WAITING_CONFIRM → RUNNING → SUCCEEDED
 *                  ├→ FAILED
 *                  └→ CANCELLED
 * </pre>
 *
 * <p>持久化为 {@code agent_task.status}（VARCHAR(20)），入库用 {@link #name()} 而非
 * {@code ordinal()}，避免枚举顺序调整后历史数据错位。
 */
public enum AgentTaskStatus {

    /** 已创建，尚未开始执行 */
    PENDING,
    /** 正在执行（规划或执行阶段） */
    RUNNING,
    /** 计划已产出，等待用户确认后才能继续（docs/AGENT.md §5） */
    WAITING_CONFIRM,
    /** 成功结束 */
    SUCCEEDED,
    /** 失败结束，{@code agent_task.error} 必须说明原因 */
    FAILED,
    /** 被用户或系统取消 */
    CANCELLED;

    /** 终态：不再接受任何流转 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
