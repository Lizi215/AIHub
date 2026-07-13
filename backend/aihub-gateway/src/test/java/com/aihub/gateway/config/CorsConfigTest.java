package com.aihub.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CORS 配置产出（docs/SECURITY.md §3）：显式来源白名单、禁止通配 + 凭证。
 *
 * <p>框架级预检行为交给 E2E 套件，Mock 下 {@code CorsWebFilter} 的判定与真实环境不一致。
 */
@DisplayName("网关 CorsConfig")
class CorsConfigTest {

    private final CorsConfig config = new CorsConfig();

    @Test
    @DisplayName("来源列表：逗号分隔并去空白，空白项被忽略")
    void shouldParseOriginsAndIgnoreBlanks() {
        CorsConfiguration configuration = config.corsConfiguration(
                " http://localhost:5173 ,, http://127.0.0.1:5173 ");

        assertThat(configuration.getAllowedOrigins())
                .containsExactly("http://localhost:5173", "http://127.0.0.1:5173");
    }

    @Test
    @DisplayName("配置里出现通配 \"*\" → 启动期即拒绝（否则运行时才炸，等于任意站点可带凭证调用）")
    void wildcardOriginShouldBeRejectedAtStartup() {
        assertThatThrownBy(() -> config.corsConfiguration("*,http://localhost:5173"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许通配");
    }

    @Test
    @DisplayName("必须携带凭证 + 暴露 X-Trace-Id；方法覆盖读写全量")
    void shouldCarryCredentialsAndExposeTraceId() {
        CorsConfiguration configuration = config.corsConfiguration("http://localhost:5173");

        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getExposedHeaders()).containsExactly("X-Trace-Id");
        assertThat(configuration.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        assertThat(configuration.getMaxAge()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("请求头白名单必须包含 Authorization（否则带 token 的跨域请求会被浏览器拦下）")
    void shouldAllowAuthorizationHeader() {
        CorsConfiguration configuration = config.corsConfiguration("http://localhost:5173");

        assertThat(configuration.getAllowedHeaders())
                .contains("Authorization", "Content-Type", "X-Trace-Id");
    }

    @Test
    @DisplayName("只有空白时不产生任何来源项（空来源等于放开任意站点）")
    void shouldNeverProduceEmptyOrigin() {
        List<String> origins = config.corsConfiguration("  ").getAllowedOrigins();

        assertThat(origins).isEmpty();
    }
}
