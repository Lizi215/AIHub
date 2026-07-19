package com.aihub.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建 Workspace 请求。
 *
 * <p>items 来自 AI 草案或用户手选，不可信任：每个 assetId 都会经 Feign 向 Asset Service
 * 校验存在、已发布、版本可下载。
 *
 * @param items 资源条目，1~20 个；同一资源只允许出现一次
 */
public record CreateWorkspaceRequest(

        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        /** 简介，AI 草案生成，可空 */
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Valid
        @NotEmpty(message = "items must not be empty")
        @Size(max = 20, message = "items must contain at most 20 entries")
        List<Item> items
) {

    public record Item(
            @NotNull(message = "assetId is required")
            @Positive(message = "assetId must be positive")
            Long assetId,

            @Size(max = 50, message = "version must be at most 50 characters")
            String version
    ) {
    }
}
