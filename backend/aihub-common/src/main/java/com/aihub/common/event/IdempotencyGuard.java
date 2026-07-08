package com.aihub.common.event;

import com.aihub.common.redis.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 消费幂等守卫。同一 eventId 重复消费只生效一次。
 *
 * <p>用 Redis {@code SET NX EX} 抢占，抢占成功即首次处理。MQ 是「至少一次」投递，
 * 重试与重复投递都是常态，没有幂等守卫就会出现重复数据。
 */
public class IdempotencyGuard {

    private final StringRedisTemplate redisTemplate;

    /** 幂等标记保留时长，必须明显长于消息重试窗口 */
    private final Duration ttl;

    public IdempotencyGuard(StringRedisTemplate redisTemplate, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    /**
     * 抢占事件的消费权。
     *
     * @return {@code true} 表示首次处理，应继续；{@code false} 表示重复投递，应跳过
     */
    public boolean tryAcquire(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // 没有 eventId 就无法保证幂等，失败关闭而不是放行
            return false;
        }
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(RedisKeys.eventIdempotency(eventId), "1", ttl));
    }

    /**
     * 处理失败时释放消费权，让后续重试仍能生效。
     *
     * <p>如果不释放，一次失败就会被永久判为「已消费」，消息重试全部变成空转。
     */
    public void release(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        redisTemplate.delete(RedisKeys.eventIdempotency(eventId));
    }
}
