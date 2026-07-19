package com.aihub.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 */
public record LoginRequest(

        @NotBlank(message = "username is required")
        @Size(max = 50, message = "username length must not exceed 50")
        String username,

        @NotBlank(message = "password is required")
        @Size(max = 72, message = "password length must not exceed 72")
        String password
) {
}
