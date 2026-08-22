package com.aihub.community.enums;

/**
 * 帖子类型（docs/ARCHITECTURE.md §7）。
 *
 * <p>Experience / Guide / Question 是 `post.type` 的取值，**不是独立实体**，
 * 也不对应独立的 ES doc_type（索引里统一是 `post`）。
 */
public enum PostType {

    EXPERIENCE,
    GUIDE,
    QUESTION;

    /**
     * 解析类型字符串，非法取值明确报错而不是落到默认类型。
     */
    public static PostType parse(String type) {
        if (type == null || type.isBlank()) {
            throw new com.aihub.common.exception.BizException(
                    com.aihub.common.result.ErrorCode.PARAM_INVALID, "post type is required");
        }
        try {
            return valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.aihub.common.exception.BizException(
                    com.aihub.common.result.ErrorCode.PARAM_INVALID, "unknown post type: " + type);
        }
    }
}
