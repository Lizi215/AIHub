package com.aihub.community.enums;

/**
 * Issue 状态机（docs/ARCHITECTURE.md §7）。
 *
 * <p>Issue 需要关联 Asset 与 Asset Version，字段与普通帖子差异大，因此独立建模而不是塞进 `post`。
 */
public enum IssueStatus {

    /** 待处理 */
    OPEN,

    /** 处理中 */
    PROCESSING,

    /** 已关闭 */
    CLOSED
}
