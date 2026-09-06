package com.aihub.ai.agent.guard;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;

/**
 * Token 成本限额（docs/AGENT.md §7「必须有限额与降级策略」）。
 *
 * <p>超出预算的降级就是终止：预算烧完的任务继续跑只会更贵，结果也大概率不可信。
 * 终止原因写进 {@code agent_task.error}，用户能看到「因为超出 Token 预算而停止」，
 * 而不是一个莫名其妙的空结果。
 */
public final class TokenBudgetGuard {

    private TokenBudgetGuard() {
    }

    /**
     * @param usedTokens 已消耗（估算或实际）
     * @param maxTokens  预算上限
     * @throws BizException {@link ErrorCode#AGENT_TOKEN_BUDGET_EXCEEDED}
     */
    public static void assertWithinBudget(int usedTokens, int maxTokens) {
        if (maxTokens > 0 && usedTokens > maxTokens) {
            throw new BizException(ErrorCode.AGENT_TOKEN_BUDGET_EXCEEDED,
                    "agent task exceeded token budget: used " + usedTokens + ", limit " + maxTokens);
        }
    }

    /**
     * Token 估算。
     *
     * <p>无 Key 的 mock 模式没有真实用量可读，只能估算；估算偏保守（按 4 字符 1 token），
     * 宁可提前触发限额，也不让预算形同虚设。
     */
    public static int estimate(String... texts) {
        int chars = 0;
        if (texts != null) {
            for (String text : texts) {
                if (text != null) {
                    chars += text.length();
                }
            }
        }
        return chars / 4 + 1;
    }
}
