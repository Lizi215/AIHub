package com.aihub.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量删除版本请求。
 *
 * @param versions 要删除的版本号列表（≤20 个；不存在的版本幂等跳过）
 */
public record DeleteAssetVersionsRequest(
        @NotEmpty(message = "versions is required")
        @Size(max = 20, message = "versions must contain at most 20 entries")
        List<@NotBlank(message = "version must not be blank") String> versions) {
}
