package com.aihub.search.config;

import com.aihub.common.event.IdempotencyGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 事件消费相关装配。守卫逻辑在 common，Bean 定义放在使用方，与 aihub-user 保持一致。
 */
@Configuration(proxyBeanMethods = false)
public class EventConfig {

    @Bean
    public IdempotencyGuard idempotencyGuard(
            StringRedisTemplate redisTemplate,
            @Value("${aihub.event.idempotency-ttl:24h}") Duration ttl) {
        return new IdempotencyGuard(redisTemplate, ttl);
    }
}
