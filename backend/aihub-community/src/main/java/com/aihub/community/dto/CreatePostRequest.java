package com.aihub.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发帖请求。
 *
 * @param type    EXPERIENCE / GUIDE / QUESTION
 * @param assetId 可选，关联资源
 */
public record CreatePostRequest(

        @NotBlank(message = "type is required")
        String type,

        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title length must not exceed 200")
        String title,

        @NotBlank(message = "content is required")
        String content,

        Long assetId
) {
}
