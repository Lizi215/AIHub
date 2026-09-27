package com.aihub.asset.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.entity.AssetTagEntity;
import com.aihub.asset.entity.AssetVersionEntity;
import com.aihub.asset.entity.TagEntity;
import com.aihub.asset.mapper.AssetMapper;
import com.aihub.asset.mapper.AssetTagMapper;
import com.aihub.asset.mapper.AssetVersionMapper;
import com.aihub.asset.mapper.TagMapper;
import com.aihub.asset.service.AssetAccessGuard;
import com.aihub.asset.service.OutboxEventService;
import com.aihub.asset.service.TagService;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 标签实现。
 *
 * <p>创建幂等靠 {@code uk_tag_name (name, deleted)} 唯一索引兜底并发，冲突时捕获重查，
 * 而不是"先查后插"留竞态窗口；整组替换实现为软删既有关联 + 插入新名单，
 * 比逐个增删更容易保证最终一致。
 *
 * <p>标签名会进 ES 的 tags keyword 字段，超长会被 ES 拒绝，因此写入前统一去空白、去重、
 * 限长限数（≤10 个、每个 ≤20 字符）。
 *
 * <p>大小写不敏感：字典表排序规则为 {@code utf8mb4_general_ci}，"MCP" 与 "mcp" 是同一个标签，
 * 唯一索引也按此判定。因此服务层的去重键统一取小写，否则同一次提交里的 "MCP"/"mcp"
 * 会绕过 Java 侧去重、生成两条指向同一 tagId 的关联。展示沿用字典里已存的写法。
 */
@Service
public class TagServiceImpl implements TagService {

    private static final int MAX_TAGS_PER_ASSET = 10;
    private static final int MAX_NAME_LENGTH = 20;

    private final TagMapper tagMapper;
    private final AssetTagMapper assetTagMapper;
    private final AssetAccessGuard accessGuard;
    private final AssetMapper assetMapper;
    private final AssetVersionMapper assetVersionMapper;
    private final OutboxEventService outboxEventService;

    public TagServiceImpl(TagMapper tagMapper, AssetTagMapper assetTagMapper, AssetAccessGuard accessGuard,
                          AssetMapper assetMapper, AssetVersionMapper assetVersionMapper,
                          OutboxEventService outboxEventService) {
        this.tagMapper = tagMapper;
        this.assetTagMapper = assetTagMapper;
        this.accessGuard = accessGuard;
        this.assetMapper = assetMapper;
        this.assetVersionMapper = assetVersionMapper;
        this.outboxEventService = outboxEventService;
    }

    @Override
    @Transactional
    public Long create(String name) {
        return findOrCreate(cleanName(name)).getId();
    }

    /**
     * 按名取标签，不存在则建。返回既有记录时其 {@code name} 就是字典里的规范写法，
     * 调用方据此回显，避免把用户输入的 "mcp" 直接当成展示名。
     */
    private TagEntity findOrCreate(String cleaned) {
        TagEntity existing = findByName(cleaned);
        if (existing != null) {
            return existing;
        }
        TagEntity tag = new TagEntity();
        tag.setName(cleaned);
        tag.setDeleted(BaseEntity.NOT_DELETED);
        try {
            tagMapper.insert(tag);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下同名：唯一索引兜底，回查既有记录（幂等语义）
            TagEntity winner = findByName(cleaned);
            if (winner == null) {
                throw e;
            }
            return winner;
        }
        return tag;
    }

    @Override
    @Transactional
    public List<String> addAssetTags(Long assetId, List<String> names, Long ownerId) {
        // 复用「整组替换」逻辑做归属校验与清洗，再把新标签并进去，避免两套清洗规则漂移
        List<String> existing = tagsOf(assetId);
        Map<String, Boolean> merged = new LinkedHashMap<>();
        for (String tag : existing) {
            merged.put(tag, Boolean.TRUE);
        }
        for (String tag : names == null ? List.<String> of() : names) {
            if (tag != null && !tag.isBlank()) {
                merged.put(tag.trim(), Boolean.TRUE);
            }
        }
        return setAssetTags(assetId, List.copyOf(merged.keySet()), ownerId);
    }

    @Override
    public String nameOf(Long assetId) {
        AssetEntity asset = assetMapper.selectById(assetId);
        return asset == null ? null : asset.getName();
    }

    @Override
    @Transactional
    public List<String> setAssetTags(Long assetId, List<String> names, Long ownerId) {
        // owner only：标签是资源内容的一部分（docs/ARCHITECTURE.md §9.2 走归属）
        AssetEntity asset = accessGuard.requireOwned(assetId, ownerId);

        // 清洗：去空白、去重、限长限数——这些名字会进 ES keyword 字段，超长会被 ES 拒绝。
        // 去重键取小写：字典排序规则是 ci，"MCP" 与 "mcp" 是同一标签，用小写键才不会重复关联。
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (String name : names == null ? List.<String>of() : names) {
            String trimmed = (name == null ? "" : name.strip());
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_NAME_LENGTH) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "tag must be at most " + MAX_NAME_LENGTH + " characters: " + trimmed);
            }
            cleaned.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
        if (cleaned.size() > MAX_TAGS_PER_ASSET) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "at most " + MAX_TAGS_PER_ASSET + " tags per asset");
        }

        // 软删既有关联（整组替换语义），再按名单重建
        AssetTagEntity softDelete = new AssetTagEntity();
        softDelete.setDeleted(System.currentTimeMillis());
        assetTagMapper.update(softDelete, new LambdaQueryWrapper<AssetTagEntity>()
                .eq(AssetTagEntity::getAssetId, assetId)
                .eq(AssetTagEntity::getDeleted, BaseEntity.NOT_DELETED));

        List<String> result = new ArrayList<>();
        for (String name : cleaned.values()) {
            TagEntity tag = findOrCreate(name);
            // 回显字典里的规范写法：用户输 "mcp"、字典里是 "MCP" 时，返回的应是 "MCP"
            result.add(tag.getName());
            AssetTagEntity relation = new AssetTagEntity();
            relation.setAssetId(assetId);
            relation.setTagId(tag.getId());
            relation.setDeleted(BaseEntity.NOT_DELETED);
            assetTagMapper.insert(relation);
        }

        // 标签变化会改变 ES 索引内容（tags 字段用于搜索筛选），与发布/下架同样
        // 走「opVersion 递增 + 同事务写 outbox」的索引同步路径，否则 ES 永远停在旧标签
        long newOpVersion = (asset.getOpVersion() == null ? 0L : asset.getOpVersion()) + 1;
        AssetEntity update = new AssetEntity();
        update.setId(assetId);
        update.setOpVersion(newOpVersion);
        assetMapper.updateById(update);

        AssetVersionEntity latest = asset.getLatestVersion() == null ? null
                : assetVersionMapper.selectOne(new LambdaQueryWrapper<AssetVersionEntity>()
                        .eq(AssetVersionEntity::getAssetId, assetId)
                        .eq(AssetVersionEntity::getVersion, asset.getLatestVersion())
                        .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED));
        asset.setOpVersion(newOpVersion);
        outboxEventService.appendAssetUploaded(asset, asset.getLatestVersion(),
                latest == null ? null : latest.getReadme(), newOpVersion);
        return result;
    }

    @Override
    public Map<Long, List<String>> tagsOfAssets(Collection<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of();
        }
        List<AssetTagEntity> links = assetTagMapper.selectList(new LambdaQueryWrapper<AssetTagEntity>()
                .in(AssetTagEntity::getAssetId, assetIds)
                .eq(AssetTagEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> tagIdsByAsset = links.stream()
                .collect(Collectors.groupingBy(AssetTagEntity::getAssetId,
                        Collectors.mapping(AssetTagEntity::getTagId, Collectors.toList())));
        List<Long> allTagIds = tagIdsByAsset.values().stream()
                .flatMap(List::stream).distinct().toList();
        Map<Long, String> names = tagMapper.selectList(new LambdaQueryWrapper<TagEntity>()
                        .in(TagEntity::getId, allTagIds)
                        .eq(TagEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream().collect(Collectors.toMap(TagEntity::getId, TagEntity::getName));
        Map<Long, List<String>> result = new java.util.HashMap<>();
        tagIdsByAsset.forEach((assetId, ids) -> result.put(assetId, ids.stream()
                .map(names::get).filter(java.util.Objects::nonNull).sorted().toList()));
        return result;
    }
    @Override
    public List<String> tagsOf(Long assetId) {
        List<Long> tagIds = assetTagMapper.selectList(new LambdaQueryWrapper<AssetTagEntity>()
                .eq(AssetTagEntity::getAssetId, assetId)
                .eq(AssetTagEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream().map(AssetTagEntity::getTagId).toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tagMapper.selectList(new LambdaQueryWrapper<TagEntity>()
                .in(TagEntity::getId, tagIds)
                .eq(TagEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream().map(TagEntity::getName).sorted().toList();
    }

    private TagEntity findByName(String name) {
        return tagMapper.selectOne(new LambdaQueryWrapper<TagEntity>()
                .eq(TagEntity::getName, name)
                .eq(TagEntity::getDeleted, BaseEntity.NOT_DELETED));
    }

    private String cleanName(String name) {
        if (StrUtil.isBlank(name)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tag name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "tag must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }
}
