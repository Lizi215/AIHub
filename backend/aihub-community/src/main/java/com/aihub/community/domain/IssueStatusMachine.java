package com.aihub.community.domain;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.community.enums.IssueStatus;

import java.util.Map;
import java.util.Set;

/**
 * Issue 状态机流转规则（docs/ARCHITECTURE.md §7）。
 *
 * <pre>
 * OPEN ──开始处理──► PROCESSING ──处理完──► CLOSED
 *  │                                        │
 *  └──────────── 直接关闭 ──────────────────┘
 *                                           │
 *                                    重新打开 ▼
 *                                         OPEN
 * </pre>
 *
 * <p>允许的流转是有限集合，其余（含原地不动）一律拒绝。
 */
public final class IssueStatusMachine {

    /** from → 允许到达的状态集合 */
    private static final Map<IssueStatus, Set<IssueStatus>> ALLOWED = Map.of(
            // 待处理可以进入处理中，也可以直接关闭（例如确认不需要处理）
            IssueStatus.OPEN, Set.of(IssueStatus.PROCESSING, IssueStatus.CLOSED),
            // 处理中只能关闭
            IssueStatus.PROCESSING, Set.of(IssueStatus.CLOSED),
            // 已关闭可以重新打开（问题复现是常态）
            IssueStatus.CLOSED, Set.of(IssueStatus.OPEN)
    );

    private IssueStatusMachine() {
    }

    /**
     * 判断流转是否允许。
     */
    public static boolean canTransit(IssueStatus from, IssueStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 校验流转，不允许则抛业务异常。
     *
     * @throws BizException {@link ErrorCode#ISSUE_STATUS_TRANSITION_INVALID}
     */
    public static void assertCanTransit(IssueStatus from, IssueStatus to) {
        if (!canTransit(from, to)) {
            throw new BizException(ErrorCode.ISSUE_STATUS_TRANSITION_INVALID,
                    "cannot transit issue status from " + from + " to " + to);
        }
    }

    /**
     * 解析状态字符串，非法取值直接拒绝而不是当成默认值。
     */
    public static IssueStatus parse(String status) {
        if (status == null || status.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "issue status is required");
        }
        try {
            return IssueStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "unknown issue status: " + status);
        }
    }
}
