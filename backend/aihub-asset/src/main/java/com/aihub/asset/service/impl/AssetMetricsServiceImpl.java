package com.aihub.asset.service.impl;

import com.aihub.asset.mapper.AssetMapper;
import com.aihub.asset.service.AssetMetricsService;
import com.aihub.asset.vo.HotAsset;
import com.aihub.asset.vo.HotAssetVo;
import com.aihub.common.redis.RedisKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 资源指标实现。
 *
 * <p>每个 Key 都必须有 TTL，禁止永久膨胀（docs/DATA.md §3）。因此只在「Key 尚无 TTL」时
 * 设置过期时间：若每次写入都 expire，只要还有人访问 Key 就不会过期，等同于没有 TTL。
 */
@Service
public class AssetMetricsServiceImpl implements AssetMetricsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redisTemplate;
    private final AssetMapper assetMapper;
    private final Duration downloadDedupWindow;
    private final Duration hotRankTtl;

    public AssetMetricsServiceImpl(StringRedisTemplate redisTemplate,
                                   AssetMapper assetMapper,
                                   @Value("${aihub.asset.metrics.download-dedup-window:24h}") Duration downloadDedupWindow,
                                   @Value("${aihub.asset.metrics.hot-rank-ttl:2d}") Duration hotRankTtl) {
        this.redisTemplate = redisTemplate;
        this.assetMapper = assetMapper;
        this.downloadDedupWindow = downloadDedupWindow;
        this.hotRankTtl = hotRankTtl;
    }

    @Override
    public boolean recordDownload(Long assetId, Long userId, String version) {
        if (assetId == null || userId == null) {
            return false;
        }
        // 先抢去重标记：抢不到说明窗口内已计过，直接忽略，避免刷量
        Boolean first = redisTemplate.opsForValue().setIfAbsent(
                RedisKeys.assetDownloadDedup(assetId, version == null ? "latest" : version, userId),
                "1",
                downloadDedupWindow);
        if (!Boolean.TRUE.equals(first)) {
            return false;
        }

        String rankKey = todayRankKey();
        redisTemplate.opsForZSet().incrementScore(rankKey, String.valueOf(assetId), 1D);
        ensureTtl(rankKey, hotRankTtl);
        return true;
    }

    @Override
    public List<HotAsset> topAssets(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(todayRankKey(), 0, limit - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
                .filter(tuple -> tuple.getValue() != null)
                .map(tuple -> new HotAsset(Long.valueOf(tuple.getValue()),
                        tuple.getScore() == null ? 0D : tuple.getScore()))
                .toList();
    }

    @Override
    public List<HotAssetVo> hotAssets(int limit) {
        List<HotAsset> top = topAssets(limit);
        if (top.isEmpty()) {
            return List.of();
        }
        // selectByIds 一次取回名字（避免逐条查询）；被删除/下架的资源不在结果里，直接不展示
        Map<Long, com.aihub.asset.entity.AssetEntity> assets = assetMapper
                .selectByIds(top.stream().map(HotAsset::assetId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.aihub.asset.entity.AssetEntity::getId,
                        java.util.function.Function.identity(),
                        (left, right) -> left));
        // 公开榜只展示「仍在库且已发布」的资源：SUSPENDED（下架）不出现在榜上
        return top.stream()
                .filter(entry -> {
                    com.aihub.asset.entity.AssetEntity asset = assets.get(entry.assetId());
                    return asset != null
                            && com.aihub.asset.enums.AssetStatus.PUBLISHED.name().equals(asset.getStatus());
                })
                .map(entry -> new HotAssetVo(entry.assetId(),
                        assets.get(entry.assetId()).getName(),
                        entry.score()))
                .toList();
    }

    /** 热门榜按天分段：跨天自然形成冷榜，旧榜靠 TTL 清理 */
    private String todayRankKey() {
        return RedisKeys.hotAssetRank(LocalDate.now().format(DAY));
    }

    /**
     * 仅在 Key 还没有 TTL 时设置过期。
     *
     * <p>每次都 expire 会让 Key 随访问无限续命，等于没有 TTL。
     */
    private void ensureTtl(String key, Duration ttl) {
        Long current = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (current == null || current < 0) {
            redisTemplate.expire(key, ttl);
        }
    }
}
