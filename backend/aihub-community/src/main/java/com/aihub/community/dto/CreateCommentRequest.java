package com.aihub.community.dto;

import jakarta.validation.constraints.NotBlank;

/** 发评论。 */
public record CreateCommentRequest(

        @NotBlank(message = "content is required")
        String content
) {
}
