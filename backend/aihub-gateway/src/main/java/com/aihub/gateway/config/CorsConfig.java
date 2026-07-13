package com.aihub.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 统一配置（docs/SECURITY.md §3：白名单，禁止 {@code *} 与携带凭证同时出现，
 * 否则任意站点都能带 Cookie 调用）。
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter(
            @Value("${aihub.gateway.cors.allowed-origins}") String allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration(allowedOrigins));
        return new CorsWebFilter(source);
    }

    /**
     * 构造 CORS 配置（包内可见，便于单测直接断言配置本身）。
     *
     * <p>单测不跑 {@link CorsWebFilter}：Mock 环境下的预检行为与真实 HTTP 不一致，
     * 框架级预检行为交给 E2E 套件，这里只锁定配置产出。
     */
    CorsConfiguration corsConfiguration(String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        // 启动期否决通配来源：Spring 在 allowCredentials=true 时遇到 "*" 会在**请求时**
        // 抛 IllegalArgumentException，那已经是线上故障；这里配置阶段就拒绝，问题暴露在启动
        if (origins.stream().anyMatch(origin -> "*".equals(origin))) {
            throw new IllegalStateException(
                    "aihub.gateway.cors.allowed-origins 不允许通配 \"*\"（与 allowCredentials=true 冲突，"
                            + "等于允许任意站点携带凭证调用），请配置显式来源白名单");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Origin",
                "X-Requested-With", "X-Trace-Id"));
        configuration.setExposedHeaders(List.of("X-Trace-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        return configuration;
    }
}
