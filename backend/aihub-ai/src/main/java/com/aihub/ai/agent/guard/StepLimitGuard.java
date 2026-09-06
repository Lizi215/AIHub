package com.aihub.ai.agent.guard;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;

/**
 * 步数上限（docs/AGENT.md §7「最大步数：必须有上限，超出即终止」）。
 *
 * <p>没有上限的 Agent 循环只会无限烧钱：一次被注入的 README 足以让它不停调用工具。
 */
public final class StepLimitGuard {

    private StepLimitGuard() {
    }

    /**
     * @param executedSteps 已执行步数
     * @param maxSteps      上限
     * @throws BizException {@link ErrorCode#AGENT_STEP_LIMIT_EXCEEDED}
     */
    public static void assertWithinLimit(int executedSteps, int maxSteps) {
        if (executedSteps >= maxSteps) {
            throw new BizException(ErrorCode.AGENT_STEP_LIMIT_EXCEEDED,
                    "agent task exceeded max steps: " + maxSteps);
        }
    }
}
