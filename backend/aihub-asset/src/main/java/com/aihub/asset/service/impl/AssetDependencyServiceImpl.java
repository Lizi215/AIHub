package com.aihub.asset.service.impl;

import com.aihub.asset.dependency.DependencyNode;
import com.aihub.asset.dependency.DependencyResolver;
import com.aihub.asset.entity.AssetDependencyEntity;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.mapper.AssetDependencyMapper;
import com.aihub.asset.mapper.AssetMapper;
import com.aihub.asset.service.AssetAccessGuard;
import com.aihub.asset.service.AssetDependencyService;
import com.aihub.asset.vo.AssetDependencyVo;
import com.aihub.asset.vo.DependencyNodeVo;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资源依赖实现。
 */
@Service
public class AssetDependencyServiceImpl implements AssetDependencyService {

    private static final Logger log = LoggerFactory.getLogger(AssetDependencyServiceImpl.class);

    private final AssetDependencyMapper dependencyMapper;
    private final AssetMapper assetMapper;
    private final AssetAccessGuard accessGuard;
    private final int maxDepth;

    public AssetDependencyServiceImpl(AssetDependencyMapper dependencyMapper,
                                      AssetMapper assetMapper,
                                      AssetAccessGuard accessGuard,
                                      @Value("${aihub.asset.dependency-max-depth:32}") int maxDepth) {
        this.dependencyMapper = dependencyMapper;
        this.assetMapper = assetMapper;
        this.accessGuard = accessGuard;
        this.maxDepth = maxDepth;
    }

    @Override
    @Transactional
    public List<String> syncFromManifest(Long assetId, List<com.aihub.asset.dependency.PackageDependencyParser.Requirement> requirements,
                                         boolean declaredInPackage) {
        boolean declared = declaredInPackage && requirements != null && !requirements.isEmpty();
        List<com.aihub.asset.dependency.PackageDependencyParser.Requirement> effective =
                requirements == null ? List.of() : requirements;
        AssetEntity self = assetMapper.selectById(assetId);
        String selfName = self == null ? null : self.getName();

        // 现有依赖（未删除），用于做"声明里没有就移除"的差集
        List<AssetDependencyEntity> existing = dependencyMapper.selectList(
                new LambdaQueryWrapper<AssetDependencyEntity>()
                        .eq(AssetDependencyEntity::getAssetId, assetId)
                        .eq(AssetDependencyEntity::getDeleted, BaseEntity.NOT_DELETED));
        java.util.Map<Long, AssetDependencyEntity> existingByTarget = new java.util.LinkedHashMap<>();
        for (AssetDependencyEntity row : existing) {
            existingByTarget.put(row.getDependsOnAssetId(), row);
        }

        List<String> notes = new java.util.ArrayList<>();
        java.util.Set<Long> keptTargets = new java.util.LinkedHashSet<>();
        int resolved = 0;
        for (com.aihub.asset.dependency.PackageDependencyParser.Requirement requirement : effective) {
            String name = requirement.name();
            if (selfName != null && selfName.equalsIgnoreCase(name)) {
                notes.add("跳过自依赖：「" + name + "」就是本资源自身");
                continue;
            }
            // 依赖目标：任何人的已发布资源，或本资源归属者自己的草稿。
            // 支持自己的草稿是为了批量上传——同一批资源互相依赖时，被依赖者此刻还是草稿；
            // 但不允许把别人的草稿当依赖（那是越权读）。
            AssetEntity target = findDependencyTarget(name, self == null ? null : self.getOwnerId());
            if (target == null) {
                notes.add("未找到可依赖的资源「" + name + "」（须为已发布资源，或本资源归属者自己的草稿），已跳过");
                continue;
            }
            if (target.getId().equals(assetId)) {
                notes.add("跳过自依赖：「" + name + "」就是本资源自身");
                continue;
            }
            // 成环与重复的写时校验复用 DependencyResolver；依赖图必须无环是
            // docs/ARCHITECTURE.md §8.4 的硬要求，上传声明也不例外
            try {
                resolver().assertCanDependOn(assetId, target.getId());
            } catch (com.aihub.common.exception.BizException e) {
                if (e.getErrorCode() == ErrorCode.ASSET_DEPENDENCY_ALREADY_EXISTS) {
                    // 这条边本来就在：保留即可，不算错误
                    keptTargets.add(target.getId());
                    resolved++;
                    continue;
                }
                notes.add("跳过依赖「" + name + "」：会形成循环依赖");
                continue;
            }
            keptTargets.add(target.getId());
            resolved++;
            if (existingByTarget.containsKey(target.getId())) {
                continue;
            }
            AssetDependencyEntity entity = new AssetDependencyEntity();
            entity.setAssetId(assetId);
            entity.setDependsOnAssetId(target.getId());
            entity.setVersionConstraint(requirement.versionConstraint());
            entity.setDeleted(BaseEntity.NOT_DELETED);
            try {
                dependencyMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                // 并发上传同一资源时唯一索引兜底：已有行即视为已同步
                log.info("dependency already exists while syncing manifest, assetId={}, target={}",
                        assetId, target.getId());
            }
        }

        // 声明里没提到的旧依赖一并移除：以最新声明为准，避免残留过时依赖
        int removed = 0;
        for (AssetDependencyEntity row : existing) {
            if (keptTargets.contains(row.getDependsOnAssetId())) {
                continue;
            }
            AssetDependencyEntity update = new AssetDependencyEntity();
            update.setId(row.getId());
            update.setDeleted(System.currentTimeMillis());
            dependencyMapper.updateById(update);
            removed++;
        }
        if (resolved > 0) {
            notes.add("已解析 " + resolved + " 条依赖");
        }
        if (removed > 0) {
            // 依赖以最新版本的声明为准：声明为空（或包内没有 aihub.yaml）时也要清掉旧依赖，
            // 否则详情页会挂着新版本早已不依赖的旧依赖
            notes.add(declared
                    ? "本次声明未包含的旧依赖已移除 " + removed + " 条"
                    : "本次包内没有 aihub.yaml（视为空声明）：已移除此前的 " + removed + " 条依赖");
        }
        log.info("dependencies synced from manifest, assetId={}, resolved={}, removed={}",
                assetId, resolved, removed);
        return notes;
    }

/**
     * 按名称精确匹配依赖目标：已发布资源（任何人）优先，其次是归属者自己的草稿。
     *
     * <p>支持自己的草稿是批量上传的关键：同一批资源互相依赖时，被依赖者此刻还是草稿。
     * 不允许把别人的草稿当依赖目标（草稿是私有的）。
     */
    private AssetEntity findDependencyTarget(String name, Long ownerId) {
        List<AssetEntity> published = assetMapper.selectList(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getName, name)
                .eq(AssetEntity::getStatus, com.aihub.asset.enums.AssetStatus.PUBLISHED.name())
                .eq(AssetEntity::getDeleted, BaseEntity.NOT_DELETED)
                .orderByDesc(AssetEntity::getId)
                .last("LIMIT 1"));
        if (!published.isEmpty()) {
            return published.get(0);
        }
        if (ownerId == null) {
            return null;
        }
        List<AssetEntity> ownDraft = assetMapper.selectList(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getName, name)
                .eq(AssetEntity::getOwnerId, ownerId)
                .eq(AssetEntity::getStatus, com.aihub.asset.enums.AssetStatus.DRAFT.name())
                .eq(AssetEntity::getDeleted, BaseEntity.NOT_DELETED)
                .orderByDesc(AssetEntity::getId)
                .last("LIMIT 1"));
        return ownDraft.isEmpty() ? null : ownDraft.get(0);
    }


    @Override
    public List<AssetDependencyVo> listDirect(Long assetId, Long currentUserId) {
        accessGuard.requireVisible(assetId, currentUserId);

        List<AssetDependencyEntity> rows = directRows(assetId);
        if (rows.isEmpty()) {
            return List.of();
        }
        // 批量取被依赖资源，避免逐行查询造成 N+1
        Map<Long, AssetEntity> targets = loadAssets(
                rows.stream().map(AssetDependencyEntity::getDependsOnAssetId).toList());

        return rows.stream()
                .map(row -> {
                    AssetEntity target = targets.get(row.getDependsOnAssetId());
                    return new AssetDependencyVo(
                            row.getId(),
                            row.getDependsOnAssetId(),
                            target == null ? null : target.getName(),
                            target == null ? null : target.getLatestVersion(),
                            row.getVersionConstraint());
                })
                .toList();
    }

    @Override
    public List<DependencyNodeVo> dependencyTree(Long assetId, Long currentUserId) {
        accessGuard.requireVisible(assetId, currentUserId);

        List<DependencyNode> nodes = resolver().buildTree(assetId);
        if (nodes.isEmpty()) {
            return List.of();
        }
        // 先把树里出现的所有资源一次性查出来再组装，同样是为了避免 N+1
        Map<Long, AssetEntity> assets = loadAssets(collectIds(nodes));
        return toVo(nodes, assets);
    }

    /* ------------------------------------------------------------------ 内部方法 */

    private DependencyResolver resolver() {
        return new DependencyResolver(this::directDependencyIds, maxDepth);
    }

    private List<Long> directDependencyIds(Long assetId) {
        return directRows(assetId).stream()
                .map(AssetDependencyEntity::getDependsOnAssetId)
                .toList();
    }

    private List<AssetDependencyEntity> directRows(Long assetId) {
        return dependencyMapper.selectList(new LambdaQueryWrapper<AssetDependencyEntity>()
                .eq(AssetDependencyEntity::getAssetId, assetId)
                .eq(AssetDependencyEntity::getDeleted, BaseEntity.NOT_DELETED));
    }

    private Map<Long, AssetEntity> loadAssets(List<Long> assetIds) {
        List<Long> distinct = assetIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        // 用按 id 批量查询：语义就是「按 id 取一批」
        return assetMapper.selectByIds(distinct).stream()
                .collect(Collectors.toMap(AssetEntity::getId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
    }

    private List<Long> collectIds(List<DependencyNode> nodes) {
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        collectIds(nodes, ids, seen);
        return ids;
    }

    private void collectIds(List<DependencyNode> nodes, List<Long> ids, Set<Long> seen) {
        for (DependencyNode node : nodes) {
            if (seen.add(node.assetId())) {
                ids.add(node.assetId());
            }
            collectIds(node.children(), ids, seen);
        }
    }

    private List<DependencyNodeVo> toVo(List<DependencyNode> nodes, Map<Long, AssetEntity> assets) {
        List<DependencyNodeVo> result = new ArrayList<>(nodes.size());
        for (DependencyNode node : nodes) {
            AssetEntity asset = assets.get(node.assetId());
            result.add(new DependencyNodeVo(
                    node.assetId(),
                    asset == null ? null : asset.getName(),
                    node.cycle(),
                    toVo(node.children(), assets)));
        }
        return result;
    }
}
