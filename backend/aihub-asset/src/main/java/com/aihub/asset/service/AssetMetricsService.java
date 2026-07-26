package com.aihub.asset.service;

import com.aihub.asset.vo.HotAsset;

import java.util.List;

/**
 * 资源指标的 Redis 侧读写（docs/DATA.md §3）。
 *
 * <p>Redis 不是唯一事实来源：这里的计数与榜单都必须能以 MySQL 为准重建。
 */
public interface AssetMetricsService {

    /**
     * 记录一次下载。
     *
     * <p>同一用户对同一 asset + version 在时间窗口内只计一次，
     * 避免热门榜被刷（docs/SECURITY.md §3）。
     *
     * @return {@code true} 表示本次计入统计；{@code false} 表示窗口内重复，已忽略
     */
    boolean recordDownload(Long assetId, Long userId, String version);

    /**
     * 今日热门资源榜。
     *
     * @param limit 取前 N 条
     */
    List<HotAsset> topAssets(int limit);

    /**
     * 今日热门资源榜（带资源名，对外展示用）。
     *
     * <p>榜单只存 id，名字以 MySQL 为准回查；被删除/下架的资源不出现。
     */
    List<com.aihub.asset.vo.HotAssetVo> hotAssets(int limit);
}
