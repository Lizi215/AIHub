package com.aihub.gateway.filter;

import com.aihub.common.auth.AuthenticatedUser;
import com.aihub.common.auth.JwtProperties;
import com.aihub.common.auth.JwtSupport;
import com.aihub.common.redis.RedisKeys;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.trace.TraceConstants;
import com.aihub.gateway.support.GatewayResponseWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关鉴权过滤器：白名单矩阵、token 校验、身份头剥离/注入、黑名单。
 */
@DisplayName("网关 AuthGlobalFilter")
class AuthGlobalFilterTest {

    private static final String SECRET = "test-secret-for-gateway-unit-tests-0123456789";

    private JwtSupport jwtSupport;
    private ReactiveStringRedisTemplate redisTemplate;
    private GatewayResponseWriter responseWriter;
    private ObjectMapper objectMapper;
    private AuthGlobalFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("aihub-test");
        properties.setAccessTokenTtl(Duration.ofMinutes(30));
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        jwtSupport = new JwtSupport(properties);

        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        objectMapper = new ObjectMapper();
        responseWriter = new GatewayResponseWriter(objectMapper);
        filter = new AuthGlobalFilter(jwtSupport, redisTemplate, responseWriter);
    }

    /** 放行但返回空 Mono 的链。 */
    private GatewayFilterChain chainThatRecords(ArgumentCaptor<ServerWebExchange> captor) {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(captor.capture())).thenReturn(Mono.empty());
        return chain;
    }

    private GatewayFilterChain plainChain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    private String bodyOf(MockServerHttpResponse response) {
        DataBuffer buffer = response.getBody().blockFirst();
        if (buffer == null) {
            return "";
        }
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private int codeOf(MockServerHttpResponse response) throws Exception {
        JsonNode node = objectMapper.readTree(bodyOf(response));
        return node.path("code").asInt();
    }

    // ------------------------------------------------------------ 白名单矩阵

    /**
     * 公开规则必须精确到「方法 + 路径」：GET 允许匿名，同路径的写方法必须登录。
     * 矩阵独立写在测试里（不复用实现里的常量），否则实现改错了测试也跟着错。
     */
    static Stream<Arguments> publicRules() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/auth/register"),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/login"),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/refresh"),
                Arguments.of(HttpMethod.GET, "/api/v1/ping"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets/2105212847813312514"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets/2105212847813312514/download"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets/2105212847813312514/versions"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets/1/versions/1.0.0/download"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets/1/versions/1.0.0/readme"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets/1/dependencies"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets/1/dependency-tree"),
                Arguments.of(HttpMethod.GET, "/api/v1/posts"),
                Arguments.of(HttpMethod.GET, "/api/v1/posts/1"),
                Arguments.of(HttpMethod.GET, "/api/v1/issues"),
                Arguments.of(HttpMethod.GET, "/api/v1/issues/1"),
                Arguments.of(HttpMethod.GET, "/api/v1/issues/1/comments"),
                Arguments.of(HttpMethod.GET, "/api/v1/posts/1/comments"),
                Arguments.of(HttpMethod.GET, "/api/v1/search/assets"),
                Arguments.of(HttpMethod.GET, "/api/v1/search/assets/suggest"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets/1/tags"),
                // 热门榜是公开可读的榜单（Phase 4）
                Arguments.of(HttpMethod.GET, "/api/v1/assets/hot"),
                // 非 /api/ 路径（actuator 等）不归本过滤器管
                Arguments.of(HttpMethod.GET, "/actuator/health"),
                Arguments.of(HttpMethod.GET, "/actuator/env"));
    }

    @ParameterizedTest(name = "公开：{0} {1}")
    @MethodSource("publicRules")
    @DisplayName("公开规则匿名放行（不带 token）")
    void publicRulesShouldPassAnonymously(HttpMethod method, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path).build());
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);

        filter.filter(exchange, chainThatRecords(captor)).block();

        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(exchange.getResponse().isCommitted()).isFalse();
    }

    /**
     * 最危险的越权面：把公开规则的路径换成写方法/越权方法，必须要求登录。
     * 例如 GET /api/v1/assets 公开，但 POST /api/v1/assets（创建资源）绝不能匿名。
     */
    static Stream<Arguments> mustRequireAuth() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/assets"),
                Arguments.of(HttpMethod.PUT, "/api/v1/assets/1"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/assets/1"),
                Arguments.of(HttpMethod.PUT, "/api/v1/assets/1/publish"),
                Arguments.of(HttpMethod.PUT, "/api/v1/assets/1/suspend"),
                Arguments.of(HttpMethod.PUT, "/api/v1/assets/1/tags"),
                Arguments.of(HttpMethod.POST, "/api/v1/assets/1/versions"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/assets/1/versions/1.0.0"),
                Arguments.of(HttpMethod.POST, "/api/v1/assets/1/dependencies"),
                Arguments.of(HttpMethod.POST, "/api/v1/posts"),
                Arguments.of(HttpMethod.POST, "/api/v1/issues"),
                Arguments.of(HttpMethod.GET, "/api/v1/users/me"),
                Arguments.of(HttpMethod.PUT, "/api/v1/users/1/role"),
                Arguments.of(HttpMethod.POST, "/api/v1/workspaces"),
                Arguments.of(HttpMethod.GET, "/api/v1/assets/own-by-name"),
                // Agent 接口必须登录：SSE 订阅、任务创建、确认、取消
                Arguments.of(HttpMethod.POST, "/api/v1/agent/tasks"),
                Arguments.of(HttpMethod.GET, "/api/v1/agent/tasks/1"),
                Arguments.of(HttpMethod.GET, "/api/v1/agent/tasks/1/stream"),
                Arguments.of(HttpMethod.POST, "/api/v1/agent/tasks/1/confirm"));
    }

    @ParameterizedTest(name = "需鉴权：{0} {1}")
    @MethodSource("mustRequireAuth")
    @DisplayName("写/私有接口无 token → 10001 未登录（不能因为路径相似被误放行）")
    void protectedEndpointsWithoutTokenShouldReturnUnauthorized(HttpMethod method, String path)
            throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path).build());
        GatewayFilterChain chain = plainChain();

        filter.filter(exchange, chain).block();

        assertThat(codeOf(exchange.getResponse())).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
        verify(chain, never()).filter(any());
    }

    // ------------------------------------------------------------ 身份头处理

    @Nested
    @DisplayName("伪造身份头")
    class IdentityHeaderGuard {

        @Test
        @DisplayName("匿名请求伪造 X-User-Id / X-User-Role → 必须被剥离")
        void anonymousForgedHeadersShouldBeStripped() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/assets")
                            .header(TraceConstants.USER_ID_HEADER, "1")
                            .header(TraceConstants.USER_ROLE_HEADER, "ADMIN")
                            .build());
            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);

            filter.filter(exchange, chainThatRecords(captor)).block();

            ServerHttpRequest forwarded = captor.getValue().getRequest();
            assertThat(forwarded.getHeaders().getFirst(TraceConstants.USER_ID_HEADER)).isNull();
            assertThat(forwarded.getHeaders().getFirst(TraceConstants.USER_ROLE_HEADER)).isNull();
        }

        @Test
        @DisplayName("带有效 token 时伪造头被覆盖为 token 里的真实身份")
        void forgedHeadersShouldBeOverwrittenByTokenIdentity() {
            String token = jwtSupport.createAccessToken(2102648648985014273L, "USER");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/users/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header(TraceConstants.USER_ID_HEADER, "1")
                            .header(TraceConstants.USER_ROLE_HEADER, "ADMIN")
                            .build());
            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);

            filter.filter(exchange, chainThatRecords(captor)).block();

            ServerHttpRequest forwarded = captor.getValue().getRequest();
            assertThat(forwarded.getHeaders().getFirst(TraceConstants.USER_ID_HEADER))
                    .isEqualTo("2102648648985014273");
            assertThat(forwarded.getHeaders().getFirst(TraceConstants.USER_ROLE_HEADER))
                    .isEqualTo("USER");
        }

        @Test
        @DisplayName("公开路径 + 有效 token → 也解析出身份（owner 才能看到自己的草稿）")
        void publicPathWithTokenShouldCarryIdentity() {
            String token = jwtSupport.createAccessToken(7L, "USER");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/assets")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .build());
            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);

            filter.filter(exchange, chainThatRecords(captor)).block();

            assertThat(captor.getValue().getRequest().getHeaders()
                    .getFirst(TraceConstants.USER_ID_HEADER)).isEqualTo("7");
        }
    }

    // ------------------------------------------------------------ token 校验

    @Nested
    @DisplayName("token 校验")
    class TokenValidation {

        @Test
        @DisplayName("垃圾 token → 10006 invalid token")
        void garbageTokenShouldBeInvalid() throws Exception {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/users/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                            .build());

            filter.filter(exchange, plainChain()).block();

            assertThat(codeOf(exchange.getResponse())).isEqualTo(ErrorCode.TOKEN_INVALID.getCode());
        }

        @Test
        @DisplayName("过期 token → 10007 token expired（前端据此决定续期而不是重新登录）")
        void expiredTokenShouldReportExpired() throws Exception {
            JwtProperties expired = new JwtProperties();
            expired.setSecret(SECRET);
            expired.setIssuer("aihub-test");
            expired.setAccessTokenTtl(Duration.ofSeconds(-10));
            String token = new JwtSupport(expired).createAccessToken(1L, "USER");

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/users/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .build());

            filter.filter(exchange, plainChain()).block();

            assertThat(codeOf(exchange.getResponse())).isEqualTo(ErrorCode.TOKEN_EXPIRED.getCode());
        }

        @Test
        @DisplayName("用别的密钥签发的 token（签名不符）→ invalid")
        void tokenSignedWithOtherSecretShouldBeRejected() throws Exception {
            JwtProperties other = new JwtProperties();
            other.setSecret("another-secret-completely-different-000000");
            other.setIssuer("aihub-test");
            String forged = new JwtSupport(other).createAccessToken(1L, "ADMIN");

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/users/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                            .build());

            filter.filter(exchange, plainChain()).block();

            assertThat(codeOf(exchange.getResponse())).isEqualTo(ErrorCode.TOKEN_INVALID.getCode());
        }

        @Test
        @DisplayName("Authorization 前缀畸形（小写 bearer / 空 token / 无 Bearer）→ 视为未带 token")
        void malformedAuthorizationHeaderTreatedAsAnonymous() throws Exception {
            for (String header : new String[]{"bearer abc", "Bearer ", "Bearer    ", "abc"}) {
                MockServerWebExchange exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/v1/users/me")
                                .header(HttpHeaders.AUTHORIZATION, header)
                                .build());

                filter.filter(exchange, plainChain()).block();

                assertThat(codeOf(exchange.getResponse()))
                        .as("header=%s 应视为未登录", header)
                        .isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
            }
        }

        @Test
        @DisplayName("黑名单里的 jti（已注销）→ 即使签名与有效期都对也拒绝")
        void blacklistedTokenShouldBeRejected() throws Exception {
            String token = jwtSupport.createAccessToken(9L, "USER");
            AuthenticatedUser parsed = jwtSupport.verifyAndParse(token);
            when(redisTemplate.hasKey(RedisKeys.accessTokenBlacklist(parsed.jwtId())))
                    .thenReturn(Mono.just(true));

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/users/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .build());

            filter.filter(exchange, plainChain()).block();

            assertThat(codeOf(exchange.getResponse())).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
        }

        @Test
        @DisplayName("公开路径 + 过期 token → 仍按匿名放行（不能因为 token 过期就看不了公开内容）")
        void publicPathWithExpiredTokenShouldStillPass() {
            JwtProperties expired = new JwtProperties();
            expired.setSecret(SECRET);
            expired.setIssuer("aihub-test");
            expired.setAccessTokenTtl(Duration.ofSeconds(-10));
            String token = new JwtSupport(expired).createAccessToken(1L, "USER");

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/assets")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .build());
            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);

            filter.filter(exchange, chainThatRecords(captor)).block();

            assertThat(captor.getAllValues()).hasSize(1);
            assertThat(exchange.getResponse().isCommitted()).isFalse();
        }
    }

    @Test
    @DisplayName("过滤器顺序早于路由转发（HIGHEST_PRECEDENCE + 100）")
    void orderShouldRunBeforeRouting() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE + 100);
    }
}
