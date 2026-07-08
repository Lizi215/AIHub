package com.aihub.common.config;

import com.aihub.common.auth.JwtSupport;
import com.aihub.common.auth.JwtUserContextInterceptor;
import com.aihub.common.exception.GlobalExceptionHandler;
import com.aihub.common.trace.TraceIdServletFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Servlet（Spring MVC）栈的公共装配：全局异常 + traceId 过滤器 + JWT 拦截器。
 *
 * <p>通过 {@code @ConditionalOnWebApplication(SERVLET)} 与 WebFlux 栈隔离：Gateway 基于 WebFlux，
 * 不会加载这里的 Bean，Servlet API 也不会进入其运行时。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
public class CommonWebMvcAutoConfiguration {

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public FilterRegistrationBean<TraceIdServletFilter> traceIdServletFilter() {
        FilterRegistrationBean<TraceIdServletFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceIdServletFilter());
        // 必须最先执行：后续过滤器与业务日志都依赖 MDC 中已存在的 traceId
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public WebMvcConfigurer aihubJwtWebMvcConfigurer(JwtSupport jwtSupport) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                // 拦截全部路径：公开接口允许匿名通过，需要登录的接口由 @RequireLogin 声明
                registry.addInterceptor(new JwtUserContextInterceptor(jwtSupport)).addPathPatterns("/**");
            }
        };
    }
}
