package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.enums.AgentTaskStatus;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * Agent 任务状态机（docs/AGENT.md §4）。
 *
 * <pre>
 * PENDING ──► RUNNING ──► SUCCEEDED
 *    │           │
 *    │           ├──► WAITING_CONFIRM ──► RUNNING（确认后继续）
 *    │           ├──► FAILED
 *    │           └──► CANCELLED
 *    └───────────────► CANCELLED
 * </pre>
 *
 * <p>允许的流转是有限集合，其余一律拒绝，包括「原地不动」。Agent 任务的错误通常不表现为
 * 报错，而是任务永远停在 {@code RUNNING}、或终态被覆盖，没有显式拒绝就只能在线上靠人工翻库
 * 发现。另有两条硬规则：终态不可再流转；{@code WAITING_CONFIRM → SUCCEEDED} 不是合法路径，
 * 必须经 {@code RUNNING} 才能结束，禁止跳过确认后的执行阶段直接宣布成功。
 */
public final class AgentTaskStateMachine {

    /** from → 允许到达的状态集合 */
    private static final Map<AgentTaskStatus, Set<AgentTaskStatus>> ALLOWED = Map.of(
            // 创建后即可执行，也允许在真正开始前取消
            AgentTaskStatus.PENDING, Set.of(AgentTaskStatus.RUNNING, AgentTaskStatus.CANCELLED),
            // 执行中：结束、失败、取消，或挂起等待确认
            AgentTaskStatus.RUNNING, Set.of(
                    AgentTaskStatus.SUCCEEDED,
                    AgentTaskStatus.WAITING_CONFIRM,
                    AgentTaskStatus.FAILED,
                    AgentTaskStatus.CANCELLED),
            // 挂起只能被确认（回到 RUNNING）或被取消
            AgentTaskStatus.WAITING_CONFIRM, Set.of(AgentTaskStatus.RUNNING, AgentTaskStatus.CANCELLED),
            // 终态：无出边
            AgentTaskStatus.SUCCEEDED, Set.of(),
            AgentTaskStatus.FAILED, Set.of(),
            AgentTaskStatus.CANCELLED, Set.of()
    );

    private AgentTaskStateMachine() {
    }

    /** 判断流转是否允许。 */
    public static boolean canTransit(AgentTaskStatus from, AgentTaskStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 校验流转，不允许则抛业务异常。
     *
     * @throws BizException {@link ErrorCode#AGENT_TASK_STATUS_TRANSITION_INVALID}
     */
    public static void assertCanTransit(AgentTaskStatus from, AgentTaskStatus to) {
        if (!canTransit(from, to)) {
            throw new BizException(ErrorCode.AGENT_TASK_STATUS_TRANSITION_INVALID,
                    "cannot transit agent task status from " + from + " to " + to);
        }
    }

    /**
     * 解析状态字符串，非法取值直接拒绝而不是当成默认值。
     *
     * <p>历史数据里若出现未知状态，宁可报错也不要「猜」，猜错会让任务停在一个
     * 谁都解释不了的状态上。
     */
    public static AgentTaskStatus parse(String status) {
        if (status == null || status.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "agent task status is required");
        }
        try {
            return AgentTaskStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "unknown agent task status: " + status);
        }
    }
}
