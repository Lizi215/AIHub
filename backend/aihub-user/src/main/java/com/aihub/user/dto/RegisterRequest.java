package com.aihub.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>密码上限 72：BCrypt 只用前 72 字节，超出部分被静默忽略，直接拒绝比让用户误以为长密码更安全。
 */
public record RegisterRequest(

        @NotBlank(message = "username is required")
        @Size(min = 3, max = 50, message = "username length must be between 3 and 50")
        String username,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password length must be between 8 and 72")
        String password,

        @Size(max = 50, message = "nickname length must not exceed 50")
        String nickname,

        @Email(message = "email format is invalid")
        @Size(max = 100, message = "email length must not exceed 100")
        String email
) {
}
