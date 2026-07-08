package com.aihub.common.config;

import com.aihub.common.trace.TraceIdWebFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * WebFlux（Reactive）栈的公共装配。Gateway 是唯一使用方。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class CommonWebFluxAutoConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public TraceIdWebFilter traceIdWebFilter() {
        return new TraceIdWebFilter();
    }
}
