package com.aihub.community.domain;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.community.enums.IssueStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 状态机。
 *
 * <p>状态机出错往往不报错，只会让数据停在不该有的状态，所以允许与拒绝两侧都钉死。
 */
class IssueStatusMachineTest {

    @Test
    @DisplayName("允许的流转：待处理→处理中→已关闭")
    void shouldAllowHappyPath() {
        assertThatCode(() -> IssueStatusMachine.assertCanTransit(IssueStatus.OPEN, IssueStatus.PROCESSING))
                .doesNotThrowAnyException();
        assertThatCode(() -> IssueStatusMachine.assertCanTransit(IssueStatus.PROCESSING, IssueStatus.CLOSED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("允许的流转：待处理可直接关闭")
    void shouldAllowOpenToClosed() {
        assertThat(IssueStatusMachine.canTransit(IssueStatus.OPEN, IssueStatus.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("允许的流转：已关闭可重新打开（问题复现是常态）")
    void shouldAllowReopen() {
        assertThat(IssueStatusMachine.canTransit(IssueStatus.CLOSED, IssueStatus.OPEN)).isTrue();
    }

    @Test
    @DisplayName("拒绝回退：处理中不能退回待处理")
    void shouldRejectBackwardTransition() {
        assertThatThrownBy(() -> IssueStatusMachine.assertCanTransit(IssueStatus.PROCESSING, IssueStatus.OPEN))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ISSUE_STATUS_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("拒绝原地不动：同一状态不算流转，默默接受会掩盖调用方的错误")
    void shouldRejectSelfTransition() {
        for (IssueStatus status : IssueStatus.values()) {
            assertThat(IssueStatusMachine.canTransit(status, status))
                    .as("self transition should be rejected: %s", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("空值一律拒绝，不能当成合法流转")
    void shouldRejectNull() {
        assertThat(IssueStatusMachine.canTransit(null, IssueStatus.OPEN)).isFalse();
        assertThat(IssueStatusMachine.canTransit(IssueStatus.OPEN, null)).isFalse();
    }

    @Test
    @DisplayName("解析状态字符串：大小写与空格容错，非法取值明确报错而不是给默认值")
    void shouldParseStatusSafely() {
        assertThat(IssueStatusMachine.parse("processing")).isEqualTo(IssueStatus.PROCESSING);
        assertThat(IssueStatusMachine.parse("  OPEN  ")).isEqualTo(IssueStatus.OPEN);

        assertThatThrownBy(() -> IssueStatusMachine.parse("BOGUS"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThatThrownBy(() -> IssueStatusMachine.parse("  "))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }
}
