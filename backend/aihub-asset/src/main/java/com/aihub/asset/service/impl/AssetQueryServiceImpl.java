package com.aihub.asset.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aihub.api.dto.AssetIndexDoc;
import com.aihub.api.dto.AssetQueryDetail;
import com.aihub.api.dto.AssetQueryItem;
import com.aihub.api.dto.AssetQueryVersion;
import com.aihub.api.dto.DependencyNodeItem;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.entity.AssetTagEntity;
import com.aihub.asset.entity.TagEntity;
import com.aihub.asset.entity.AssetFileEntity;
import com.aihub.asset.entity.AssetVersionEntity;
import com.aihub.asset.enums.AssetStatus;
import com.aihub.asset.enums.VersionPublishStatus;
import com.aihub.asset.mapper.AssetFileMapper;
import com.aihub.asset.mapper.AssetTagMapper;
import com.aihub.asset.mapper.TagMapper;
import com.aihub.asset.mapper.AssetMapper;
import com.aihub.asset.mapper.AssetVersionMapper;
import com.aihub.asset.service.AssetAccessGuard;
import com.aihub.asset.service.AssetDependencyService;
import com.aihub.asset.service.AssetQueryService;
import com.aihub.asset.vo.DependencyNodeVo;
import com.aihub.common.config.JacksonConfig;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 内部只读查询实现。
 *
 * <p>所有查询都按「未登录访客」执行（{@code currentUserId = null}），草稿与已下架资源不会出现在结果里。
 * 本类只读，不写任何表，也不调用 File Service——Agent 解析下载地址即可。
 */
@Service
public class AssetQueryServiceImpl implements AssetQueryService {

    /**
     * 下载地址模板。返回相对 Gateway 的路径而非绝对 URL：绝对 URL 会把部署地址
     * 硬编码进服务间数据，换环境就失效。
     */
    private static final String DOWNLOAD_PATH_TEMPLATE = "/api/v1/assets/%d/versions/%s/download";

    /** Agent 只用于制定计划，单个资源最多回传的版本数——防止上下文被版本历史撑爆 */
    private static final int MAX_VERSIONS = 20;

    private final AssetMapper assetMapper;
    private final AssetTagMapper assetTagMapper;
    private final TagMapper tagMapper;
    private final AssetVersionMapper assetVersionMapper;
    private final AssetFileMapper assetFileMapper;
    private final AssetDependencyService dependencyService;
    private final AssetAccessGuard accessGuard;

    public AssetQueryServiceImpl(AssetMapper assetMapper,
                                 AssetTagMapper assetTagMapper,
                                 TagMapper tagMapper,
                                 AssetVersionMapper assetVersionMapper,
                                 AssetFileMapper assetFileMapper,
                                 AssetDependencyService dependencyService,
                                 AssetAccessGuard accessGuard) {
        this.assetMapper = assetMapper;
        this.assetTagMapper = assetTagMapper;
        this.tagMapper = tagMapper;
        this.assetVersionMapper = assetVersionMapper;
        this.assetFileMapper = assetFileMapper;
        this.dependencyService = dependencyService;
        this.accessGuard = accessGuard;
    }

    @Override
    public PageResult<AssetQueryItem> search(String keyword, String category, String tag, PageQuery query) {
        LambdaQueryWrapper<AssetEntity> wrapper = new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getDeleted, BaseEntity.NOT_DELETED)
                // 匿名可见性：只返回已发布资源
                .eq(AssetEntity::getStatus, AssetStatus.PUBLISHED.name());
        if (StrUtil.isNotBlank(category)) {
            wrapper.eq(AssetEntity::getCategory, category.trim());
        }
        if (StrUtil.isNotBlank(tag)) {
            // 标签筛选走两段查询（不用 Wrapper 硬拼 JOIN，docs/BACKEND.md §4）：
            // 先解析标签名 → 挂了该标签的 assetId 集合，再 in 过滤
            List<Long> taggedIds = assetIdsByTag(tag.trim());
            if (taggedIds.isEmpty()) {
                // 该标签下没有资源：直接空页，SQL 不必再跑
                return PageResult.of(List.of(), 0, query.pageOrDefault(), query.sizeOrDefault());
            }
            wrapper.in(AssetEntity::getId, taggedIds);
        }
        if (StrUtil.isNotBlank(keyword)) {
            String trimmed = keyword.trim();
            // 名称或简介命中即可；用 and(...) 包起来，避免 or() 把上面的可见性条件也放行
            wrapper.and(condition -> condition
                    .like(AssetEntity::getName, trimmed)
                    .or()
                    .like(AssetEntity::getSummary, trimmed));
        }
        wrapper.orderByDesc(AssetEntity::getCreateTime);

        Page<AssetEntity> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        Page<AssetEntity> result = assetMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords().stream().map(this::toItem).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AssetQueryDetail detail(Long assetId) {
        AssetEntity asset = accessGuard.requireVisible(assetId, null);
        List<AssetQueryVersion> versions = listVersionItems(asset);
        String readme = versions.isEmpty() ? null : versions.get(0).readme();
        return new AssetQueryDetail(asset.getId(), asset.getOwnerId(), asset.getName(), asset.getSummary(),
                asset.getCategory(), asset.getStatus(), asset.getLatestVersion(), readme, versions);
    }

    @Override
    public List<DependencyNodeItem> dependencyTree(Long assetId) {
        return toNodeItems(dependencyService.dependencyTree(assetId, null));
    }

    @Override
    public PageResult<AssetIndexDoc> scanPublished(String updatedAfterIso, PageQuery query) {
        LambdaQueryWrapper<AssetEntity> wrapper = new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getDeleted, BaseEntity.NOT_DELETED)
                .eq(AssetEntity::getStatus, AssetStatus.PUBLISHED.name());
        applyUpdatedAfter(wrapper, updatedAfterIso);
        // 排序必须稳定（update_time 可能相同，追加 id 兜底），否则分页扫描会漏记录/重复
        wrapper.orderByAsc(AssetEntity::getUpdateTime).orderByAsc(AssetEntity::getId);

        Page<AssetEntity> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        Page<AssetEntity> result = assetMapper.selectPage(page, wrapper);
        List<AssetEntity> assets = result.getRecords();
        if (assets.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), result.getCurrent(), result.getSize());
        }

        // 一次取回本页资源的全部版本，再按 (assetId, latestVersion) 取最新版本，避免逐条查询造成 N+1
        Map<Long, List<AssetVersionEntity>> versionsByAsset = assetVersionMapper.selectList(
                        new LambdaQueryWrapper<AssetVersionEntity>()
                                .in(AssetVersionEntity::getAssetId, assets.stream().map(AssetEntity::getId).toList())
                                .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream()
                .collect(Collectors.groupingBy(AssetVersionEntity::getAssetId));

        Map<Long, List<String>> tagsByAsset = loadTagNames(assets.stream().map(AssetEntity::getId).toList());

        List<AssetIndexDoc> docs = new ArrayList<>(assets.size());
        for (AssetEntity asset : assets) {
            AssetVersionEntity latest = versionsByAsset.getOrDefault(asset.getId(), List.of()).stream()
                    .filter(v -> v.getVersion() != null && v.getVersion().equals(asset.getLatestVersion()))
                    .findFirst()
                    .orElse(null);
            docs.add(new AssetIndexDoc(
                    String.valueOf(asset.getId()),
                    asset.getName(),
                    asset.getSummary(),
                    latest == null ? null : latest.getReadme(),
                    tagsByAsset.getOrDefault(asset.getId(), List.of()),
                    asset.getCategory(),
                    String.valueOf(asset.getOwnerId()),
                    asset.getStatus(),
                    asset.getOpVersion() == null ? 0L : asset.getOpVersion(),
                    // 天级下载量回写未做
                    0L,
                    formatTime(asset.getCreateTime()),
                    formatTime(asset.getUpdateTime()),
                    // 与事件链路对齐：事件体里的 version 就是资源当前最新版本
                    asset.getLatestVersion()));
        }
        return PageResult.of(docs, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public PageResult<String> scanRemoved(String updatedAfterIso, PageQuery query) {
        LambdaQueryWrapper<AssetEntity> wrapper = new LambdaQueryWrapper<AssetEntity>()
                // 应移除 = 已软删除 或 已不再是 PUBLISHED。
                // 必须同时判断 deleted 与 status：删资源后状态字段仍停在 PUBLISHED，只判断 status 会漏掉。
                .and(condition -> condition
                        .ne(AssetEntity::getDeleted, BaseEntity.NOT_DELETED)
                        .or()
                        .ne(AssetEntity::getStatus, AssetStatus.PUBLISHED.name()));
        applyUpdatedAfter(wrapper, updatedAfterIso);
        wrapper.orderByAsc(AssetEntity::getUpdateTime).orderByAsc(AssetEntity::getId);

        Page<AssetEntity> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        Page<AssetEntity> result = assetMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords().stream().map(a -> String.valueOf(a.getId())).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 叠加「更新时间为增量下界」的条件。
     *
     * <p>时间解析失败直接报错而不是忽略：忽略会让对账静默退化成全量扫描，数据量上来后很难归因。
     */
    private void applyUpdatedAfter(LambdaQueryWrapper<AssetEntity> wrapper, String updatedAfterIso) {
        if (StrUtil.isBlank(updatedAfterIso)) {
            return;
        }
        try {
            wrapper.gt(AssetEntity::getUpdateTime,
                    LocalDateTime.parse(updatedAfterIso.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "invalid updatedAfter: " + updatedAfterIso);
        }
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ofPattern(JacksonConfig.DATE_TIME_PATTERN));
    }

    /* ------------------------------------------------------------------ 内部方法 */

    /** 版本按创建时间倒序，因此第 0 个是「最新创建的版本」。 */
    private List<AssetQueryVersion> listVersionItems(AssetEntity asset) {
        List<AssetVersionEntity> versions = assetVersionMapper.selectList(
                new LambdaQueryWrapper<AssetVersionEntity>()
                        .eq(AssetVersionEntity::getAssetId, asset.getId())
                        .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED)
                        .orderByDesc(AssetVersionEntity::getCreateTime));
        if (versions.isEmpty()) {
            return List.of();
        }
        if (versions.size() > MAX_VERSIONS) {
            versions = versions.subList(0, MAX_VERSIONS);
        }
        // 一次取出全部文件元数据再组装，避免逐版本查询造成 N+1
        Map<Long, AssetFileEntity> files = assetFileMapper.selectList(
                        new LambdaQueryWrapper<AssetFileEntity>()
                                .in(AssetFileEntity::getVersionId,
                                        versions.stream().map(AssetVersionEntity::getId).toList())
                                .eq(AssetFileEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream()
                .collect(Collectors.toMap(AssetFileEntity::getVersionId, Function.identity(),
                        (left, right) -> left));

        boolean assetPublished = AssetStatus.PUBLISHED.name().equals(asset.getStatus());
        List<AssetQueryVersion> items = new ArrayList<>(versions.size());
        for (AssetVersionEntity version : versions) {
            AssetFileEntity file = files.get(version.getId());
            // 可下载 = 资源已发布 且 版本已发布 且 文件元数据存在。三者缺一都会让下载侧失败，
            // 必须在计划阶段告诉 Agent，否则用户确认完才失败。
            boolean downloadable = assetPublished
                    && VersionPublishStatus.PUBLISHED.name().equals(version.getPublishStatus())
                    && file != null;
            items.add(new AssetQueryVersion(
                    version.getId(),
                    version.getVersion(),
                    version.getChangelog(),
                    version.getReadme(),
                    version.getPublishStatus(),
                    file == null ? null : file.getChecksum(),
                    file == null ? null : file.getSize(),
                    downloadable,
                    downloadable ? DOWNLOAD_PATH_TEMPLATE.formatted(asset.getId(), version.getVersion()) : null));
        }
        return items;
    }

    /**
     * 批量取资源的标签名：关联与字典两次查询后内存组装（避免逐资源 N+1）。
     */
    private Map<Long, List<String>> loadTagNames(List<Long> assetIds) {
        List<AssetTagEntity> relations = assetTagMapper.selectList(new LambdaQueryWrapper<AssetTagEntity>()
                .in(AssetTagEntity::getAssetId, assetIds)
                .eq(AssetTagEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (relations.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = tagMapper.selectList(new LambdaQueryWrapper<TagEntity>()
                .in(TagEntity::getId, relations.stream().map(AssetTagEntity::getTagId).distinct().toList())
                .eq(TagEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream()
                .collect(Collectors.toMap(TagEntity::getId, TagEntity::getName, (l, r) -> l));
        Map<Long, List<String>> byAsset = new LinkedHashMap<>();
        for (AssetTagEntity relation : relations) {
            String name = names.get(relation.getTagId());
            if (name != null) {
                byAsset.computeIfAbsent(relation.getAssetId(), k -> new ArrayList<>()).add(name);
            }
        }
        return byAsset;
    }

    /**
     * 解析标签名下的 assetId 集合（两段查询：tag 字典 → asset_tag 关联）。
     */
    private List<Long> assetIdsByTag(String tagName) {
        TagEntity tag = tagMapper.selectOne(new LambdaQueryWrapper<TagEntity>()
                .eq(TagEntity::getName, tagName)
                .eq(TagEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (tag == null) {
            return List.of();
        }
        return assetTagMapper.selectList(new LambdaQueryWrapper<AssetTagEntity>()
                .eq(AssetTagEntity::getTagId, tag.getId())
                .eq(AssetTagEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream()
                .map(AssetTagEntity::getAssetId)
                .distinct()
                .toList();
    }

    private List<DependencyNodeItem> toNodeItems(List<DependencyNodeVo> nodes) {
        List<DependencyNodeItem> items = new ArrayList<>(nodes.size());
        for (DependencyNodeVo node : nodes) {
            items.add(new DependencyNodeItem(node.assetId(), node.name(), node.cycle(),
                    toNodeItems(node.children())));
        }
        return items;
    }

    @Override
    public List<AssetQueryItem> mine(Long ownerId) {
        List<AssetEntity> rows = assetMapper.selectList(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getOwnerId, ownerId)
                .eq(AssetEntity::getDeleted, BaseEntity.NOT_DELETED)
                .orderByDesc(AssetEntity::getCreateTime)
                .last("LIMIT 50"));
        return rows.stream().map(this::toItem).toList();
    }

    private AssetQueryItem toItem(AssetEntity asset) {
        return new AssetQueryItem(asset.getId(), asset.getOwnerId(), asset.getName(), asset.getSummary(),
                asset.getCategory(), asset.getStatus(), asset.getLatestVersion());
    }
}
