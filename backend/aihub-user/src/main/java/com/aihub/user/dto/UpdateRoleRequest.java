package com.aihub.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 用户角色变更请求（Admin only）。
 *
 * <p>这里用 Pattern 先挡住明显的垃圾值，service 层还会用 Role 枚举再校验一次。
 */
public record UpdateRoleRequest(

        @NotBlank(message = "role is required")
        @Pattern(regexp = "(?i)USER|MAINTAINER|ADMIN",
                message = "role must be one of USER, MAINTAINER, ADMIN")
        String role
) {
}
