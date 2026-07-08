package com.aihub.api.config;

import com.aihub.common.trace.TraceConstants;
import com.aihub.common.trace.UserContext;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 上下文透传配置（docs/BACKEND.md §3：必须透传 traceId 与当前 userId）。
 *
 * <p>调用方需在 {@code @EnableFeignClients(defaultConfiguration = FeignTraceConfig.class)} 中显式引用本配置，
 * 不要依赖默认包扫描，否则拦截器可能未生效而链路静默断裂。
 */
@Configuration(proxyBeanMethods = false)
public class FeignTraceConfig {

    @Bean
    public RequestInterceptor tracePropagationInterceptor() {
        return template -> {
            String traceId = MDC.get(TraceConstants.MDC_TRACE_ID);
            if (traceId != null && !traceId.isBlank()) {
                template.header(TraceConstants.TRACE_ID_HEADER, traceId);
            }
            Long userId = UserContext.getUserId();
            if (userId != null) {
                template.header(TraceConstants.USER_ID_HEADER, String.valueOf(userId));
            }
            String role = UserContext.getRole();
            if (role != null) {
                template.header(TraceConstants.USER_ROLE_HEADER, role);
            }
        };
    }
}
