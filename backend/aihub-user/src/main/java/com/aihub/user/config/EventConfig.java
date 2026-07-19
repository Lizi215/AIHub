package com.aihub.user.config;

import com.aihub.common.event.IdempotencyGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 事件消费相关装配。
 *
 * <p>{@link IdempotencyGuard} 的 Bean 定义放在真正使用它的服务里，不放进 common：
 * common 的自动装配需要 {@code @ConditionalOnBean(StringRedisTemplate)} 之类的条件，
 * 而 Gateway 只有 reactive Redis，容易连带把它拖挂。
 */
@Configuration(proxyBeanMethods = false)
public class EventConfig {

    @Bean
    public IdempotencyGuard idempotencyGuard(
            StringRedisTemplate redisTemplate,
            @Value("${aihub.event.idempotency-ttl:7d}") Duration ttl) {
        return new IdempotencyGuard(redisTemplate, ttl);
    }
}
