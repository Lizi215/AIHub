package com.aihub.community.vo;

import java.time.LocalDateTime;

/** 帖子评论视图。 */
public record PostCommentVo(Long id, Long postId, Long authorId, String content, LocalDateTime createTime) {
}
