package com.aihub.user.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析，用于登录防爆破的按 IP 限流。
 *
 * <p>取 {@code X-Forwarded-For} 的最后一段，取不到则回退 {@code remoteAddr}。
 * Gateway 的 XForwardedHeadersFilter 是追加语义，客户端自带的伪造值会排在最前；
 * 取最后一段才能拿到可信 IP，否则攻击者每次换假 IP 即可绕过限流。
 */
public final class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }
}
