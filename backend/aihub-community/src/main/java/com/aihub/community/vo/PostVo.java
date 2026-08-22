package com.aihub.community.vo;

import java.time.LocalDateTime;

/** 帖子视图，列表与详情共用，正文原样返回。 */
public record PostVo(
        Long id,
        Long authorId,
        String type,
        String title,
        String content,
        Long assetId,
        Long likeCount,
        LocalDateTime createTime
) {
}
