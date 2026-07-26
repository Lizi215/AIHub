package com.aihub.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 上传阶段①：创建草稿。
 */
public record CreateAssetRequest(

        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name length must not exceed 200")
        String name,

        @Size(max = 500, message = "summary length must not exceed 500")
        String summary,

        @Size(max = 50, message = "category length must not exceed 50")
        String category
) {
}
