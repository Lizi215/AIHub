package com.aihub.community.service.impl;

import com.aihub.common.redis.RedisKeys;
import com.aihub.community.entity.PostLikeEntity;
import com.aihub.community.mapper.PostLikeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞去重。
 *
 * <p>核心是验证「Redis 只是派生数据」：Redis 抢到不等于点赞成功，只有 MySQL 插入成功才算；
 * 两边不一致时以 MySQL 为准并修正 Redis。
 */
@ExtendWith(MockitoExtension.class)
class PostLikeServiceImplTest {

    private static final long POST_ID = 500L;
    private static final long USER_ID = 7L;
    private static final Duration TTL = Duration.ofDays(30);

    @Mock
    private PostLikeMapper postLikeMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;

    private PostLikeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PostLikeServiceImpl(postLikeMapper, redisTemplate, TTL);
    }

    private String likeKey() {
        return RedisKeys.postLikeSet(POST_ID);
    }

    @Test
    @DisplayName("首次点赞：抢到去重位后插库，并给集合设置 TTL")
    void shouldLikeFirstTime() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add(likeKey(), String.valueOf(USER_ID))).thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-1L);

        assertThat(service.like(POST_ID, USER_ID)).isTrue();

        ArgumentCaptor<PostLikeEntity> captor = ArgumentCaptor.forClass(PostLikeEntity.class);
        verify(postLikeMapper).insert(captor.capture());
        assertThat(captor.getValue().getPostId()).isEqualTo(POST_ID);
        assertThat(captor.getValue().getDeleted()).isZero();
        verify(redisTemplate).expire(likeKey(), TTL);
    }

    @Test
    @DisplayName("重复点赞不重复计：Redis 命中即返回，不再插库")
    void shouldIgnoreDuplicateLike() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add(likeKey(), String.valueOf(USER_ID))).thenReturn(0L);

        assertThat(service.like(POST_ID, USER_ID)).isFalse();

        verify(postLikeMapper, never()).insert(any(PostLikeEntity.class));
    }

    @Test
    @DisplayName("Redis 丢数据导致不一致时以 MySQL 为准：捕获唯一键冲突并修正 Redis")
    void shouldTrustDatabaseWhenCacheIsStale() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add(likeKey(), String.valueOf(USER_ID))).thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(3600L);
        when(postLikeMapper.insert(any(PostLikeEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_post_like"));

        // 唯一索引拦住了重复，因此仍然是「没重复计数」，但缓存偏差要被修掉
        assertThat(service.like(POST_ID, USER_ID)).isFalse();

        verify(setOperations).remove(likeKey(), String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("已有点赞时不再重复设置 TTL：否则集合随访问无限续命")
    void shouldNotRefreshTtlWhenAlreadySet() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add(likeKey(), String.valueOf(USER_ID))).thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(100L);

        service.like(POST_ID, USER_ID);

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("取消点赞：MySQL 软删除写时间戳（而非 0/1），并清掉缓存成员")
    void shouldUnlikeWithSoftDelete() {
        PostLikeEntity existing = new PostLikeEntity();
        existing.setId(9L);
        when(postLikeMapper.selectOne(any())).thenReturn(existing);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        assertThat(service.unlike(POST_ID, USER_ID)).isTrue();

        ArgumentCaptor<PostLikeEntity> captor = ArgumentCaptor.forClass(PostLikeEntity.class);
        verify(postLikeMapper).updateById(captor.capture());
        // 写时间戳才能让「取消后再点赞」不撞唯一键
        assertThat(captor.getValue().getDeleted()).isNotNull().isGreaterThan(0L);
        verify(setOperations).remove(likeKey(), String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("取消未点过的赞：返回 false，并顺手校正缓存残留")
    void shouldReturnFalseWhenUnlikingWithoutLike() {
        when(postLikeMapper.selectOne(any())).thenReturn(null);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        assertThat(service.unlike(POST_ID, USER_ID)).isFalse();

        verify(postLikeMapper, never()).updateById(any(PostLikeEntity.class));
        verify(setOperations).remove(likeKey(), String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("缺少 userId 时不予接受：无法去重的点赞不能放行")
    void shouldRejectLikeWithoutUserId() {
        assertThat(service.like(POST_ID, null)).isFalse();

        verify(redisTemplate, never()).opsForSet();
        verify(postLikeMapper, never()).insert(any(PostLikeEntity.class));
    }

    @Test
    @DisplayName("点赞数从 MySQL 统计，避免 Redis 缓存漂移影响展示")
    void shouldCountFromDatabase() {
        when(postLikeMapper.selectCount(any())).thenReturn(3L);

        assertThat(service.likeCount(POST_ID)).isEqualTo(3L);

        verify(redisTemplate, never()).opsForSet();
    }
}
