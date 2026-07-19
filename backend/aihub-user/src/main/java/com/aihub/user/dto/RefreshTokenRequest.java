package com.aihub.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 续期请求。
 */
public record RefreshTokenRequest(

        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {
}
