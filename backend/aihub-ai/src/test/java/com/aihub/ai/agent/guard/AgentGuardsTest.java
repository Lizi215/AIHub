package com.aihub.ai.agent.guard;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 防护约束：步数上限、循环检测、Token 限额（docs/AGENT.md §7）。
 *
 * <p>这三条共同决定 Agent 不会失控地一直跑下去，因此都必须有明确的触发点，
 * 而不是「理论上会停」。
 */
class AgentGuardsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("步数上限：达到上限即终止")
    void stepLimitShouldStopAtBoundary() {
        assertThatCode(() -> StepLimitGuard.assertWithinLimit(0, 3)).doesNotThrowAnyException();
        assertThatCode(() -> StepLimitGuard.assertWithinLimit(2, 3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StepLimitGuard.assertWithinLimit(3, 3))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_STEP_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("循环检测：相同 Tool + 相同参数被判为循环")
    void loopDetectionShouldRejectSameCall() {
        Map<String, Object> args = Map.of("assetId", 100L);
        String fingerprint = LoopDetectionGuard.fingerprint("get_asset_detail", args, objectMapper);

        assertThatThrownBy(() -> LoopDetectionGuard.assertNotRepeated(Set.of(fingerprint), fingerprint))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_LOOP_DETECTED);
    }

    @Test
    @DisplayName("循环检测：参数顺序不同不算不同调用，否则换个字段顺序就能绕过")
    void loopDetectionShouldNormalizeArgOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("keyword", "spring");
        first.put("category", "SKILL");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("category", "SKILL");
        second.put("keyword", "spring");

        assertThat(LoopDetectionGuard.fingerprint("search_assets", first, objectMapper))
                .isEqualTo(LoopDetectionGuard.fingerprint("search_assets", second, objectMapper));
    }

    @Test
    @DisplayName("循环检测：同 Tool 不同参数是正常行为，不能被误判")
    void loopDetectionShouldAllowDifferentArgs() {
        String a = LoopDetectionGuard.fingerprint("search_assets", Map.of("keyword", "a"), objectMapper);
        String b = LoopDetectionGuard.fingerprint("search_assets", Map.of("keyword", "b"), objectMapper);

        assertThat(a).isNotEqualTo(b);
        assertThatCode(() -> LoopDetectionGuard.assertNotRepeated(Set.of(a), b)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Token 限额：超出预算即终止")
    void tokenBudgetShouldStopWhenExceeded() {
        assertThatCode(() -> TokenBudgetGuard.assertWithinBudget(100, 200)).doesNotThrowAnyException();
        assertThatThrownBy(() -> TokenBudgetGuard.assertWithinBudget(201, 200))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_TOKEN_BUDGET_EXCEEDED);
    }

    @Test
    @DisplayName("Token 估算：按字符数保守估算，空输入也不返回 0")
    void tokenEstimateShouldBeConservative() {
        assertThat(TokenBudgetGuard.estimate("12345678")).isEqualTo(3);
        assertThat(TokenBudgetGuard.estimate("abc", null)).isPositive();
        assertThat(TokenBudgetGuard.estimate((String[]) null)).isPositive();
    }
}
