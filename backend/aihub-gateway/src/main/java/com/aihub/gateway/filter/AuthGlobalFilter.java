package com.aihub.gateway.filter;

import com.aihub.common.auth.AuthenticatedUser;
import com.aihub.common.auth.JwtSupport;
import com.aihub.common.exception.BizException;
import com.aihub.common.redis.RedisKeys;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.trace.TraceConstants;
import com.aihub.gateway.support.GatewayResponseWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JWT 校验与身份注入（docs/ARCHITECTURE.md §1、docs/SECURITY.md §2）。
 *
 * <p>先剥离客户端自带的 X-User-Id / X-User-Role，只校验不清洗的话，调用方直接带这两个头
 * 就能冒充任意用户。随后校验签名与有效期、查 Redis 黑名单让注销的 access token 立即失效，
 * 通过后把真实 userId / role 注入请求头给下游。
 *
 * <p>公开接口允许匿名，但带了 token 仍会解析身份，资源归属方才能看到自己的草稿。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 无需登录即可访问的接口。必须精确到「方法 + 路径」：
     * GET /api/v1/assets 是公开浏览，而 POST /api/v1/assets 必须登录。
     */
    private static final List<PublicRule> PUBLIC_RULES = List.of(
            new PublicRule(HttpMethod.POST, "/api/v1/auth/register"),
            new PublicRule(HttpMethod.POST, "/api/v1/auth/login"),
            new PublicRule(HttpMethod.POST, "/api/v1/auth/refresh"),
            new PublicRule(HttpMethod.GET, "/api/v1/ping"),
            new PublicRule(HttpMethod.GET, "/api/v1/assets"),
            new PublicRule(HttpMethod.GET, "/api/v1/assets/*"),
            new PublicRule(HttpMethod.GET, "/api/v1/assets/*/download"),
            // 版本与依赖的读取也要匿名可访问：Visitor 本来就能浏览公开资源
            new PublicRule(HttpMethod.GET, "/api/v1/assets/*/versions"),
            new PublicRule(HttpMethod.GET, "/api/v1/assets/*/versions/*/download"),
            // 每个版本的 README.md 也是公开内容，与 zip 下载同一套可见性
            new PublicRule(HttpMethod.GET, "/api/v1/assets/*/versions/*/readme"),
            new PublicRule(HttpMethod.GET, "/api/v1/assets/*/dependencies"),
            new PublicRule(HttpMethod.GET, "/api/v1/assets/*/dependency-tree"),
            // 社区内容公开可读：帖子与 Issue 的浏览无需登录
            new PublicRule(HttpMethod.GET, "/api/v1/posts"),
            new PublicRule(HttpMethod.GET, "/api/v1/posts/*"),
            new PublicRule(HttpMethod.GET, "/api/v1/issues"),
            new PublicRule(HttpMethod.GET, "/api/v1/issues/*"),
            // 评论也是公开内容。注意单段通配符 `*` 匹配不到 /issues/1/comments，
            // 必须显式列出二级路径，否则读评论会被拦成 10001
            new PublicRule(HttpMethod.GET, "/api/v1/issues/*/comments"),
            new PublicRule(HttpMethod.GET, "/api/v1/posts/*/comments"),
            // 搜索公开可读：Visitor 也能检索公开资源与社区内容
            new PublicRule(HttpMethod.GET, "/api/v1/search/**"),
            // 标签字典与资源的标签公开可读
            new PublicRule(HttpMethod.GET, "/api/v1/tags"),
            new PublicRule(HttpMethod.GET, "/api/v1/assets/*/tags"));

    /**
     * 必须先于 {@link #PUBLIC_RULES} 判定的「必须登录」清单。
     *
     * <p>公开规则里的单段通配符 {@code /api/v1/assets/*} 会顺带匹配同层的字面量路径，
     * 例如 {@code GET /api/v1/assets/own-by-name}（查自己的同名资源），
     * 服务端 {@code @RequireLogin} 虽会拦住，但网关这一层也不该漏。
     */
    private static final List<PublicRule> NEVER_PUBLIC = List.of(
            new PublicRule(HttpMethod.GET, "/api/v1/assets/own-by-name"));

    private final JwtSupport jwtSupport;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayResponseWriter responseWriter;

    public AuthGlobalFilter(JwtSupport jwtSupport,
                            ReactiveStringRedisTemplate redisTemplate,
                            GatewayResponseWriter responseWriter) {
        this.jwtSupport = jwtSupport;
        this.redisTemplate = redisTemplate;
        this.responseWriter = responseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getRawPath();
        HttpMethod method = request.getMethod();
        boolean authRequired = requiresAuth(method, path);
        String token = resolveToken(request);

        if (token == null) {
            return authRequired
                    ? responseWriter.write(exchange, ErrorCode.UNAUTHORIZED)
                    : forwardAnonymous(exchange, chain);
        }

        AuthenticatedUser user;
        try {
            user = jwtSupport.verifyAndParse(token);
        } catch (BizException e) {
            // 保留具体错误码（如 TOKEN_EXPIRED），前端据此决定是续期还是重新登录
            return authRequired
                    ? responseWriter.write(exchange, e.getErrorCode())
                    : forwardAnonymous(exchange, chain);
        }

        return redisTemplate.hasKey(RedisKeys.accessTokenBlacklist(user.jwtId()))
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        // 已注销：即使签名与有效期都正确也必须拒绝
                        return authRequired
                                ? responseWriter.write(exchange, ErrorCode.UNAUTHORIZED)
                                : forwardAnonymous(exchange, chain);
                    }
                    return forwardAuthenticated(exchange, chain, user);
                });
    }

    /** 匿名转发，但仍要剥离客户端自带的身份头。 */
    private Mono<Void> forwardAnonymous(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(TraceConstants.USER_ID_HEADER);
                    headers.remove(TraceConstants.USER_ROLE_HEADER);
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    /** 校验通过的转发：剥离伪造头后注入可信身份。 */
    private Mono<Void> forwardAuthenticated(ServerWebExchange exchange, GatewayFilterChain chain,
                                            AuthenticatedUser user) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(TraceConstants.USER_ID_HEADER);
                    headers.remove(TraceConstants.USER_ROLE_HEADER);
                    headers.set(TraceConstants.USER_ID_HEADER, String.valueOf(user.userId()));
                    if (user.role() != null) {
                        headers.set(TraceConstants.USER_ROLE_HEADER, user.role());
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private boolean requiresAuth(HttpMethod method, String path) {
        if (method == null || !path.startsWith("/api/")) {
            // /actuator/** 等非业务路径不在本过滤器管辖范围
            return false;
        }
        if (NEVER_PUBLIC.stream().anyMatch(rule -> rule.matches(method, path))) {
            return true;
        }
        return PUBLIC_RULES.stream().noneMatch(rule -> rule.matches(method, path));
    }

    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    @Override
    public int getOrder() {
        // 早于路由转发，但晚于 traceId（traceId 由 WebFilter 处理，天然更早）
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    private record PublicRule(HttpMethod method, String pattern) {

        boolean matches(HttpMethod method, String path) {
            return this.method == method && PATH_MATCHER.match(pattern, path);
        }
    }
}
