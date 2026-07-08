package com.aihub.common.config;

import com.aihub.common.auth.JwtProperties;
import com.aihub.common.auth.JwtSupport;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 与 Web 栈无关的公共装配入口，Servlet / WebFlux 服务都会加载。
 *
 * <p>{@link JwtSupport} 在这里无条件装配：密钥缺失时启动即失败，而不是带着「默认密钥」跑起来。
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
@Import(JacksonConfig.class)
public class CommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtSupport jwtSupport(JwtProperties jwtProperties) {
        return new JwtSupport(jwtProperties);
    }
}
