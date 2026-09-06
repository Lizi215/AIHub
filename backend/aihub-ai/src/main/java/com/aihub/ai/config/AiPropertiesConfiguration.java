package com.aihub.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 服务配置装配。
 *
 * <p>单独一个类启用 {@link AgentProperties}，而不是在 Application 上开
 * {@code @ConfigurationPropertiesScan}：扫描会连带把其它包的属性类一并注册，
 * 这里只需要显式声明依赖这份配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class AiPropertiesConfiguration {
}
