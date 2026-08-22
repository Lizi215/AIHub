package com.aihub.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提出 Issue。
 */
public record CreateIssueRequest(

        @NotNull(message = "assetId is required")
        Long assetId,

        @Size(max = 50, message = "version length must not exceed 50")
        String version,

        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title length must not exceed 200")
        String title,

        @NotBlank(message = "content is required")
        String content
) {
}
