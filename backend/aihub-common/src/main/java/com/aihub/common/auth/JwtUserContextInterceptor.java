package com.aihub.common.auth;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.trace.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 业务服务的 JWT 拦截器（docs/SECURITY.md §2）。
 *
 * <p>从 {@code Authorization: Bearer} 解析 token，校验签名与有效期，把 userId / role 写入
 * {@link UserContext}，请求结束后清理。
 *
 * <p>token 缺失不直接报错：公开接口允许匿名；需要登录的接口用 {@link RequireLogin} 声明，由本拦截器拒绝。
 * {@code afterCompletion} 必须清理 ThreadLocal，否则线程复用会造成用户身份串号。
 *
 * <p>无效 token 在公开接口上降级为匿名，与 Gateway 的 {@code forwardAnonymous} 语义一致：
 * 若业务层直接抛错，会出现「公开资源读不了、还报 invalid token」的矛盾行为。
 * 需要登录的接口则保留原始错误码，前端据此区分未登录与 token 非法。
 */
public class JwtUserContextInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtSupport jwtSupport;

    public JwtUserContextInterceptor(JwtSupport jwtSupport) {
        this.jwtSupport = jwtSupport;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        boolean loginRequired = requiresLogin(handler);

        String token = resolveToken(request);
        if (token != null) {
            try {
                AuthenticatedUser user = jwtSupport.verifyAndParse(token);
                UserContext.set(user.userId(), user.role());
            } catch (BizException e) {
                // 公开接口按匿名放行；需要登录的接口把 token 的具体问题如实抛给调用方
                if (loginRequired) {
                    throw e;
                }
            }
        }

        if (loginRequired && UserContext.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private boolean requiresLogin(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        return handlerMethod.hasMethodAnnotation(RequireLogin.class)
                || handlerMethod.getBeanType().isAnnotationPresent(RequireLogin.class);
    }
}
