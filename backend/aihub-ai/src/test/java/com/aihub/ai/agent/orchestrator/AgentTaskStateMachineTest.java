package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.enums.AgentTaskStatus;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 任务状态机。对应 Phase 6 出口标准「搜索 → 生成计划 → 用户确认 →
 * 执行 → 汇总」这条链路的合法性约束。
 *
 * <p>状态机的错误不会抛异常，只会让任务停在不该有的状态，所以这里把「允许」与「拒绝」
 * 两侧都钉死，尤其是终态不可改，以及禁止跳过确认后的执行阶段。
 */
class AgentTaskStateMachineTest {

    @Test
    @DisplayName("允许的流转：待执行 → 执行中 → 成功")
    void shouldAllowHappyPath() {
        assertThatCode(() -> AgentTaskStateMachine.assertCanTransit(AgentTaskStatus.PENDING, AgentTaskStatus.RUNNING))
                .doesNotThrowAnyException();
        assertThatCode(() -> AgentTaskStateMachine.assertCanTransit(AgentTaskStatus.RUNNING, AgentTaskStatus.SUCCEEDED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("允许的流转：执行中 → 等待确认 → 确认后回到执行中 → 成功")
    void shouldAllowConfirmationPath() {
        assertThatCode(() -> AgentTaskStateMachine.assertCanTransit(
                AgentTaskStatus.RUNNING, AgentTaskStatus.WAITING_CONFIRM)).doesNotThrowAnyException();
        assertThatCode(() -> AgentTaskStateMachine.assertCanTransit(
                AgentTaskStatus.WAITING_CONFIRM, AgentTaskStatus.RUNNING)).doesNotThrowAnyException();
        assertThatCode(() -> AgentTaskStateMachine.assertCanTransit(
                AgentTaskStatus.RUNNING, AgentTaskStatus.SUCCEEDED)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("允许的流转：失败与取消")
    void shouldAllowFailureAndCancel() {
        assertThat(AgentTaskStateMachine.canTransit(AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED)).isTrue();
        assertThat(AgentTaskStateMachine.canTransit(AgentTaskStatus.RUNNING, AgentTaskStatus.CANCELLED)).isTrue();
        assertThat(AgentTaskStateMachine.canTransit(AgentTaskStatus.PENDING, AgentTaskStatus.CANCELLED)).isTrue();
        assertThat(AgentTaskStateMachine.canTransit(AgentTaskStatus.WAITING_CONFIRM, AgentTaskStatus.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("拒绝跳过执行阶段：等待确认不能直接变成功")
    void shouldRejectConfirmToSucceeded() {
        assertThatThrownBy(() -> AgentTaskStateMachine.assertCanTransit(
                AgentTaskStatus.WAITING_CONFIRM, AgentTaskStatus.SUCCEEDED))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_TASK_STATUS_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("拒绝回到待执行：任务一旦开始就不会退回 PENDING")
    void shouldRejectReturnToPending() {
        assertThat(AgentTaskStateMachine.canTransit(AgentTaskStatus.RUNNING, AgentTaskStatus.PENDING)).isFalse();
        assertThat(AgentTaskStateMachine.canTransit(AgentTaskStatus.WAITING_CONFIRM, AgentTaskStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("终态不可再流转，否则「已成功的任务又被改成失败」这类脏数据无从排查")
    void shouldRejectAnyTransitionFromTerminal() {
        for (AgentTaskStatus terminal : new AgentTaskStatus[]{
                AgentTaskStatus.SUCCEEDED, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED}) {
            assertThat(terminal.isTerminal()).as("%s should be terminal", terminal).isTrue();
            for (AgentTaskStatus to : AgentTaskStatus.values()) {
                assertThat(AgentTaskStateMachine.canTransit(terminal, to))
                        .as("%s -> %s should be rejected", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("拒绝原地不动：同一状态不算流转")
    void shouldRejectSelfTransition() {
        for (AgentTaskStatus status : AgentTaskStatus.values()) {
            assertThat(AgentTaskStateMachine.canTransit(status, status))
                    .as("self transition should be rejected: %s", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("空值一律拒绝，不能当成合法流转")
    void shouldRejectNull() {
        assertThat(AgentTaskStateMachine.canTransit(null, AgentTaskStatus.RUNNING)).isFalse();
        assertThat(AgentTaskStateMachine.canTransit(AgentTaskStatus.RUNNING, null)).isFalse();
    }

    @Test
    @DisplayName("解析状态字符串：大小写与空格容错，非法取值明确报错而不是给默认值")
    void shouldParseStatusSafely() {
        assertThat(AgentTaskStateMachine.parse("waiting_confirm")).isEqualTo(AgentTaskStatus.WAITING_CONFIRM);
        assertThat(AgentTaskStateMachine.parse("  RUNNING  ")).isEqualTo(AgentTaskStatus.RUNNING);

        assertThatThrownBy(() -> AgentTaskStateMachine.parse("BOGUS"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThatThrownBy(() -> AgentTaskStateMachine.parse("  "))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }
}
