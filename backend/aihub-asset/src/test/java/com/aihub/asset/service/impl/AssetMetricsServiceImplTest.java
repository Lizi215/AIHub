package com.aihub.asset.service.impl;

import com.aihub.asset.vo.HotAsset;
import com.aihub.common.redis.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资源指标的 Redis 侧行为。
 *
 * <p>重点验下载防刷量（窗口内重复不计数）与 Key 必须有 TTL（每次写入都 expire 会让 Key
 * 随访问无限续命，等于没有 TTL）。
 */
@ExtendWith(MockitoExtension.class)
class AssetMetricsServiceImplTest {

    private static final long ASSET_ID = 100L;
    private static final long USER_ID = 7L;
    private static final Duration DEDUP = Duration.ofHours(24);
    private static final Duration RANK_TTL = Duration.ofDays(2);

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private com.aihub.asset.mapper.AssetMapper assetMapper;

    private AssetMetricsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AssetMetricsServiceImpl(redisTemplate, assetMapper, DEDUP, RANK_TTL);
    }

    private String todayRankKey() {
        return RedisKeys.hotAssetRank(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
    }

    @Test
    @DisplayName("首次下载计入统计：抢到去重标记、热度 +1，并给新榜单设置 TTL")
    void shouldCountFirstDownload() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(DEDUP))).thenReturn(true);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-1L);

        assertThat(service.recordDownload(ASSET_ID, USER_ID, "1.0.0")).isTrue();

        verify(valueOperations).setIfAbsent(
                eq(RedisKeys.assetDownloadDedup(ASSET_ID, "1.0.0", USER_ID)), eq("1"), eq(DEDUP));
        verify(zSetOperations).incrementScore(todayRankKey(), String.valueOf(ASSET_ID), 1D);
        verify(redisTemplate).expire(todayRankKey(), RANK_TTL);
    }

    @Test
    @DisplayName("窗口内重复下载不计数：不增加热度，避免热门榜被反复下载刷上去")
    void shouldIgnoreDuplicateDownloadWithinWindow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(service.recordDownload(ASSET_ID, USER_ID, "1.0.0")).isFalse();

        verify(zSetOperations, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("榜单已有 TTL 时不再重复设置：每次都 expire 会让 Key 随访问无限续命")
    void shouldNotRefreshTtlWhenAlreadySet() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(DEDUP))).thenReturn(true);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(3600L);

        service.recordDownload(ASSET_ID, USER_ID, "1.0.0");

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("热门榜按分数倒序映射为 VO")
    void shouldReturnTopAssets() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of("100", 5D));
        tuples.add(ZSetOperations.TypedTuple.of("200", 3D));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeWithScores(todayRankKey(), 0L, 4L)).thenReturn(tuples);

        List<HotAsset> top = service.topAssets(5);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).assetId()).isEqualTo(100L);
        assertThat(top.get(0).score()).isEqualTo(5D);
        assertThat(top.get(1).assetId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("热门榜带名字：名字以 MySQL 为准回查，被删除/已下架的资源不出现")
    void hotAssetsShouldResolveNamesAndSkipMissing() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of("100", 5D));
        tuples.add(ZSetOperations.TypedTuple.of("200", 3D));
        tuples.add(ZSetOperations.TypedTuple.of("300", 2D));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeWithScores(todayRankKey(), 0L, 2L)).thenReturn(tuples);
        // ZSet 里有 100/200/300；selectByIds 只回 100（已发布）与 300（SUSPENDED），
        // 200 已被删除查不到——榜单必须只剩 100
        when(assetMapper.selectByIds(any()))
                .thenReturn(List.of(
                        assetWithName(100L, "Spring Cloud 配置技能", "PUBLISHED"),
                        assetWithName(300L, "已下架资源", "SUSPENDED")));

        List<com.aihub.asset.vo.HotAssetVo> hot = service.hotAssets(3);

        assertThat(hot).hasSize(1);
        assertThat(hot.get(0).assetId()).isEqualTo(100L);
        assertThat(hot.get(0).name()).isEqualTo("Spring Cloud 配置技能");
        assertThat(hot.get(0).score()).isEqualTo(5D);
    }

    private com.aihub.asset.entity.AssetEntity assetWithName(Long id, String name) {
        return assetWithName(id, name, "PUBLISHED");
    }

    private com.aihub.asset.entity.AssetEntity assetWithName(Long id, String name, String status) {
        com.aihub.asset.entity.AssetEntity asset = new com.aihub.asset.entity.AssetEntity();
        asset.setId(id);
        asset.setName(name);
        asset.setStatus(status);
        return asset;
    }

    @Test
    @DisplayName("limit 非法时直接返回空，不打 Redis")
    void shouldNotQueryRedisForInvalidLimit() {
        assertThat(service.topAssets(0)).isEmpty();

        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    @DisplayName("缺少 userId 时不计数（无法去重的下载不能计）")
    void shouldIgnoreDownloadWithoutUserId() {
        assertThat(service.recordDownload(ASSET_ID, null, "1.0.0")).isFalse();

        verify(redisTemplate, never()).opsForValue();
    }
}
