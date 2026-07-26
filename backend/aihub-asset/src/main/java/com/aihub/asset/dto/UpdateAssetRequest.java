package com.aihub.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 编辑资源元数据。
 *
 * <p>只开放 name / summary / category：它们是描述性元数据且都参与 ES 索引；
 * 状态、版本、文件、归属属于其他流程（publish / versions，归属不可改）。
 */
public record UpdateAssetRequest(

        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name length must not exceed 200")
        String name,

        @Size(max = 500, message = "summary length must not exceed 500")
        String summary,

        @Size(max = 50, message = "category length must not exceed 50")
        String category
) {
}
