package com.aihub.user.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析。这是登录防爆破的输入，取值错了限流就等于没做。
 */
class ClientIpResolverTest {

    @Test
    void shouldFallBackToRemoteAddrWhenNoForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void shouldTakeLastForwardedEntryBecauseGatewayAppends() {
        // 客户端自带 1.2.3.4，Gateway 追加真实对端 203.0.113.7 —— 取第一段就会被伪造值绕过限流
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldHandleSingleForwardedEntry() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldTrimWhitespaceAndIgnoreBlankHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "   ");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void shouldIgnoreTrailingEmptyForwardedEntry() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7,");

        // Java 的 String.split 丢弃尾部空串，这里取到最后一个非空项。
        // Gateway 追加 X-Forwarded-For，真实对端永远在末尾，因此仍满足不被伪造值绕过。
        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }
}
