package com.aihub.common.event;

import com.aihub.common.redis.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费幂等：同一 eventId 重复消费只生效一次。
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    private static final String EVENT_ID = "evt-0001";
    private static final Duration TTL = Duration.ofHours(24);

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyGuard guard;

    @Test
    @DisplayName("首次抢占成功，且抢占标记带 TTL（禁止永不过期的 Key）")
    void shouldAcquireFirstTimeWithTtl() {
        guard = newGuard(true);

        assertThat(guard.tryAcquire(EVENT_ID)).isTrue();

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).setIfAbsent(eq(RedisKeys.eventIdempotency(EVENT_ID)), eq("1"), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(TTL);
    }

    @Test
    @DisplayName("重复抢占失败，调用方据此跳过")
    void shouldRejectDuplicateConsume() {
        guard = newGuard(false);

        assertThat(guard.tryAcquire(EVENT_ID)).isFalse();
    }

    @Test
    @DisplayName("eventId 缺失时失败关闭：无法保证幂等就不能放行")
    void shouldFailClosedWithoutEventId() {
        guard = new IdempotencyGuard(redisTemplate, TTL);

        assertThat(guard.tryAcquire(null)).isFalse();
        assertThat(guard.tryAcquire("  ")).isFalse();
    }

    @Test
    @DisplayName("处理失败后释放消费权，让重试仍能生效")
    void shouldReleaseOnFailure() {
        guard = new IdempotencyGuard(redisTemplate, TTL);

        guard.release(EVENT_ID);

        verify(redisTemplate).delete(RedisKeys.eventIdempotency(EVENT_ID));
    }

    @Test
    @DisplayName("释放空 eventId 不应打 Redis")
    void shouldNotTouchRedisWhenReleasingBlank() {
        guard = new IdempotencyGuard(redisTemplate, TTL);

        guard.release("  ");

        verify(redisTemplate, org.mockito.Mockito.never()).delete(anyString());
    }

    private IdempotencyGuard newGuard(boolean acquireResult) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(acquireResult);
        return new IdempotencyGuard(redisTemplate, TTL);
    }
}
