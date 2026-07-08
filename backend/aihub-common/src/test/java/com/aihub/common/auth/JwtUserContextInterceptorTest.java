package com.aihub.common.auth;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.trace.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务服务 JWT 拦截器：身份注入、@RequireLogin 生效、ThreadLocal 清理。
 */
class JwtUserContextInterceptorTest {

    private static final long USER_ID = 1234567890123456789L;

    private final JwtSupport jwtSupport = support();
    private final JwtUserContextInterceptor interceptor = new JwtUserContextInterceptor(jwtSupport);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void shouldPopulateUserContextFromBearerToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwtSupport.createAccessToken(USER_ID, "USER"));

        interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod("open"));

        assertThat(UserContext.getUserId()).isEqualTo(USER_ID);
        assertThat(UserContext.getRole()).isEqualTo("USER");
    }

    @Test
    void shouldAllowAnonymousAccessToOpenEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod("open"));

        assertThat(result).isTrue();
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void shouldRejectAnonymousAccessToSecuredEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod("secured")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void shouldAllowSecuredEndpointWhenTokenPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwtSupport.createAccessToken(USER_ID, "USER"));

        boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod("secured"));

        assertThat(result).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void shouldClearUserContextAfterCompletionToAvoidIdentityLeak() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwtSupport.createAccessToken(USER_ID, "USER"));
        interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod("open"));
        assertThat(UserContext.getUserId()).isNotNull();

        interceptor.afterCompletion(request, new MockHttpServletResponse(), handlerMethod("open"), null);

        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getRole()).isNull();
    }

    @Test
    void shouldTreatInvalidTokenAsAnonymousOnOpenEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer this-is-not-a-valid-jwt");

        // 公开接口不能因为一个坏 token 就整体失败：网关对公开路由本就按匿名转发，
        // 业务层再抛错会让「浏览公开资源」无故报 invalid token
        boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod("open"));

        assertThat(result).isTrue();
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void shouldRejectInvalidTokenOnSecuredEndpointWithTokenError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer this-is-not-a-valid-jwt");

        // 需要登录的接口要保留「token 非法」这一具体原因，而不是笼统的未登录
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod("secured")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void shouldRejectExpiredTokenOnOpenEndpointAsAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + expiredSupport().createAccessToken(USER_ID, "USER"));

        boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod("open"));

        assertThat(result).isTrue();
        assertThat(UserContext.getUserId()).isNull();
    }

    private HandlerMethod handlerMethod(String name) throws NoSuchMethodException {
        Method method = DummyController.class.getMethod(name);
        return new HandlerMethod(new DummyController(), method);
    }

    private static JwtSupport support() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        properties.setAccessTokenTtl(Duration.ofMinutes(30));
        return new JwtSupport(properties);
    }

    /** 造一个签名合法但立刻过期的 token。 */
    private static JwtSupport expiredSupport() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        properties.setAccessTokenTtl(Duration.ofSeconds(-1));
        return new JwtSupport(properties);
    }

    /** 测试替身：只用于提供带 / 不带 @RequireLogin 的 HandlerMethod。 */
    static class DummyController {
        public void open() {
        }

        @RequireLogin
        public void secured() {
        }
    }
}
