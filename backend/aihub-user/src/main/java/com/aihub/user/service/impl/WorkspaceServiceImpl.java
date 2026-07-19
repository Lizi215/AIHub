package com.aihub.user.service.impl;

import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.dto.AssetQueryDetail;
import com.aihub.api.dto.AssetQueryVersion;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.user.dto.CreateWorkspaceRequest;
import com.aihub.user.entity.WorkspaceEntity;
import com.aihub.user.entity.WorkspaceItemEntity;
import com.aihub.user.mapper.WorkspaceItemMapper;
import com.aihub.user.mapper.WorkspaceMapper;
import com.aihub.user.service.WorkspaceService;
import com.aihub.user.vo.WorkspaceItemVo;
import com.aihub.user.vo.WorkspaceVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace 实现。
 *
 * <p>查/删都要求 {@code user_id} 匹配，Workspace 是用户私有数据。
 * 条目的存在性、发布状态、版本可下载性经 Feign 调 {@link AssetQueryApi#detail} 校验，
 * User Service 不直接读 asset 表。详情页展示的名字与可用性再查一次，
 * 这样下游下架或删版本时，历史条目会如实标成不可用。
 */
@Service
public class WorkspaceServiceImpl implements WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceServiceImpl.class);

    private static final int MAX_ITEMS = 20;

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceItemMapper workspaceItemMapper;
    private final AssetQueryApi assetQueryApi;

    public WorkspaceServiceImpl(WorkspaceMapper workspaceMapper,
                                WorkspaceItemMapper workspaceItemMapper,
                                AssetQueryApi assetQueryApi) {
        this.workspaceMapper = workspaceMapper;
        this.workspaceItemMapper = workspaceItemMapper;
        this.assetQueryApi = assetQueryApi;
    }

    @Override
    @Transactional
    public Long create(Long userId, CreateWorkspaceRequest request) {
        List<CreateWorkspaceRequest.Item> items = request.items();
        // 容量上限先查，条目数不合法就没必要继续校验
        if (items.size() > MAX_ITEMS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "items must contain at most " + MAX_ITEMS + " entries");
        }
        // 同一资源去重，重复属于输入错误而非静默合并
        Map<Long, CreateWorkspaceRequest.Item> unique = new LinkedHashMap<>();
        for (CreateWorkspaceRequest.Item item : items) {
            if (unique.putIfAbsent(item.assetId(), item) != null) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "duplicate assetId in items: " + item.assetId());
            }
        }

        // 逐项校验存在、已发布，指定版本时再校验该版本可下载。
        // 失败时给出具体条目，便于用户定位。
        for (CreateWorkspaceRequest.Item item : unique.values()) {
            AssetQueryDetail detail = requirePublishedAsset(item.assetId());
            if (item.version() != null && !item.version().isBlank()
                    && detail.versions().stream().noneMatch(v -> item.version().equals(v.version()))) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "asset " + item.assetId() + " has no version " + item.version());
            }
        }

        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setUserId(userId);
        workspace.setName(request.name().trim());
        workspace.setDescription(request.description());
        workspace.setDeleted(BaseEntity.NOT_DELETED);
        workspaceMapper.insert(workspace);

        for (CreateWorkspaceRequest.Item item : unique.values()) {
            WorkspaceItemEntity itemEntity = new WorkspaceItemEntity();
            itemEntity.setWorkspaceId(workspace.getId());
            itemEntity.setAssetId(item.assetId());
            itemEntity.setVersion(item.version());
            itemEntity.setDeleted(BaseEntity.NOT_DELETED);
            workspaceItemMapper.insert(itemEntity);
        }
        log.info("workspace created, id={}, userId={}, items={}",
                workspace.getId(), userId, unique.size());
        return workspace.getId();
    }

    @Override
    public PageResult<WorkspaceVo> list(Long userId, PageQuery query) {
        Page<WorkspaceEntity> page = workspaceMapper.selectPage(
                new Page<>(query.pageOrDefault(), query.sizeOrDefault()),
                new LambdaQueryWrapper<WorkspaceEntity>()
                        .eq(WorkspaceEntity::getUserId, userId)
                        .eq(WorkspaceEntity::getDeleted, BaseEntity.NOT_DELETED)
                        .orderByDesc(WorkspaceEntity::getCreateTime));
        List<WorkspaceVo> records = page.getRecords().stream()
                .map(w -> new WorkspaceVo(w.getId(), w.getName(), w.getDescription(), List.of(), w.getCreateTime()))
                .toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public WorkspaceVo detail(Long userId, Long id) {
        WorkspaceEntity workspace = requireOwned(id, userId);
        List<WorkspaceItemEntity> items = workspaceItemMapper.selectList(
                new LambdaQueryWrapper<WorkspaceItemEntity>()
                        .eq(WorkspaceItemEntity::getWorkspaceId, id)
                        .eq(WorkspaceItemEntity::getDeleted, BaseEntity.NOT_DELETED)
                        .orderByAsc(WorkspaceItemEntity::getId));

        List<WorkspaceItemVo> itemVos = items.stream()
                .map(item -> {
                    // 回查名字与可用性，下游下架后如实标记为不可用
                    AssetQueryDetail detail = findAssetQuietly(item.getAssetId());
                    String version = item.getVersion() == null
                            ? (detail == null ? null : detail.latestVersion())
                            : item.getVersion();
                    boolean available = detail != null && isVersionAvailable(detail, version);
                    String name = detail == null ? null : detail.name();
                    return new WorkspaceItemVo(item.getAssetId(), name, version, available);
                })
                .toList();
        return new WorkspaceVo(workspace.getId(), workspace.getName(), workspace.getDescription(),
                itemVos, workspace.getCreateTime());
    }

    @Override
    @Transactional
    public void delete(Long userId, Long id) {
        requireOwned(id, userId);
        workspaceMapper.deleteById(id);
        // 条目随之软删，不留孤儿
        WorkspaceItemEntity itemUpdate = new WorkspaceItemEntity();
        itemUpdate.setDeleted(System.currentTimeMillis());
        workspaceItemMapper.update(itemUpdate, new LambdaQueryWrapper<WorkspaceItemEntity>()
                .eq(WorkspaceItemEntity::getWorkspaceId, id)
                .eq(WorkspaceItemEntity::getDeleted, BaseEntity.NOT_DELETED));
        log.info("workspace deleted, id={}, userId={}", id, userId);
    }

    /* ------------------------------------------------------------------ 内部方法 */

    private WorkspaceEntity requireOwned(Long id, Long userId) {
        WorkspaceEntity workspace = workspaceMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceEntity>()
                        .eq(WorkspaceEntity::getId, id)
                        .eq(WorkspaceEntity::getUserId, userId)
                        .eq(WorkspaceEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (workspace == null) {
            // 不区分不存在与无权访问，避免泄露他人 workspace 的存在性
            throw new BizException(ErrorCode.NOT_FOUND, "workspace not found: " + id);
        }
        return workspace;
    }

    private AssetQueryDetail requirePublishedAsset(Long assetId) {
        Result<AssetQueryDetail> result = assetQueryApi.detail(assetId);
        if (result.code() != ErrorCode.SUCCESS.getCode() || result.data() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "asset " + assetId + " is not available (not found or not published)");
        }
        AssetQueryDetail detail = result.data();
        if (!"PUBLISHED".equals(detail.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "asset " + assetId + " is not published");
        }
        return detail;
    }

    private AssetQueryDetail findAssetQuietly(Long assetId) {
        try {
            Result<AssetQueryDetail> result = assetQueryApi.detail(assetId);
            if (result.code() == ErrorCode.SUCCESS.getCode()) {
                return result.data();
            }
            return null;
        } catch (Exception e) {
            log.warn("failed to resolve workspace item asset, assetId={}", assetId, e);
            return null;
        }
    }

    private boolean isVersionAvailable(AssetQueryDetail detail, String version) {
        return detail.versions().stream()
                .filter(v -> version == null || version.equals(v.version()))
                .findFirst()
                .map(AssetQueryVersion::downloadable)
                .orElse(false);
    }
}
