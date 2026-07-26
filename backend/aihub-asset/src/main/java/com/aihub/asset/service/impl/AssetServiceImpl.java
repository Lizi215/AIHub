package com.aihub.asset.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aihub.api.dto.FileUploadResponse;
import com.aihub.api.file.FileApi;
import com.aihub.asset.dto.CreateAssetRequest;
import com.aihub.asset.dto.UpdateAssetRequest;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.entity.AssetFileEntity;
import com.aihub.asset.entity.AssetVersionEntity;
import com.aihub.asset.enums.AssetStatus;
import com.aihub.asset.enums.VersionPublishStatus;
import com.aihub.asset.mapper.AssetFileMapper;
import com.aihub.asset.mapper.AssetMapper;
import com.aihub.asset.mapper.AssetVersionMapper;
import com.aihub.asset.service.AssetAccessGuard;
import com.aihub.asset.service.AssetService;
import com.aihub.api.event.DownloadCompletedEvent;
import com.aihub.api.event.EventTopics;
import com.aihub.asset.service.OutboxEventService;
import com.aihub.asset.service.TagService;
import com.aihub.asset.vo.AssetDetailVo;
import com.aihub.asset.vo.AssetDownload;
import com.aihub.asset.service.AssetDependencyService;
import com.aihub.asset.vo.AssetVersionVo;
import com.aihub.asset.vo.AssetVo;
import com.aihub.common.auth.Role;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.outbox.EventDispatcher;
import com.aihub.common.outbox.OutboxMessage;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.trace.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 资源核心业务实现。
 *
 * <p>上传三阶段的编排者（docs/ARCHITECTURE.md §8.1）。文件字节经本服务中转：
 * 客户端 → Gateway → Asset → Feign → File → MinIO，因此必须设大小上限，
 * 否则一个超大文件就能把本服务堆内存打满。
 */
@Service
public class AssetServiceImpl implements AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetServiceImpl.class);

    /** 资源还没有任何版本时的初始版本号 */
    private static final String DEFAULT_VERSION = "1.0.0";

    /** 可自动递增的版本号形态：major.minor.patch（纯数字） */
    private static final Pattern AUTO_INCREMENTABLE_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    private static final String OBJECT_KEY_TEMPLATE = "assets/%d/%s/package.zip";

    private final AssetMapper assetMapper;
    private final AssetVersionMapper assetVersionMapper;
    private final AssetFileMapper assetFileMapper;
    private final AssetVersionWriter assetVersionWriter;
    private final AssetAccessGuard accessGuard;
    private final OutboxEventService outboxEventService;
    private final FileApi fileApi;
    private final ObjectProvider<EventDispatcher> downloadEventDispatcher;
    private final ObjectMapper objectMapper;
    private final TagService tagService;
    private final com.aihub.asset.support.AuthorNameResolver authorNameResolver;
    private final com.aihub.asset.dependency.PackageDependencyParser packageDependencyParser;
    private final AssetDependencyService assetDependencyService;
    private final boolean downloadEventEnabled;
    private final long maxUploadSizeBytes;

    public AssetServiceImpl(AssetMapper assetMapper,
                            AssetVersionMapper assetVersionMapper,
                            AssetFileMapper assetFileMapper,
                            AssetVersionWriter assetVersionWriter,
                            AssetAccessGuard accessGuard,
                            OutboxEventService outboxEventService,
                            FileApi fileApi,
                            ObjectProvider<EventDispatcher> downloadEventDispatcher,
                            ObjectMapper objectMapper,
                            TagService tagService,
                            com.aihub.asset.support.AuthorNameResolver authorNameResolver,
                            com.aihub.asset.dependency.PackageDependencyParser packageDependencyParser,
                            AssetDependencyService assetDependencyService,
                            @Value("${aihub.asset.max-upload-size-bytes:52428800}") long maxUploadSizeBytes,
                            @Value("${aihub.asset.download-event.enabled:true}") boolean downloadEventEnabled) {
        this.assetMapper = assetMapper;
        this.assetVersionMapper = assetVersionMapper;
        this.assetFileMapper = assetFileMapper;
        this.assetVersionWriter = assetVersionWriter;
        this.accessGuard = accessGuard;
        this.outboxEventService = outboxEventService;
        this.fileApi = fileApi;
        this.downloadEventDispatcher = downloadEventDispatcher;
        this.objectMapper = objectMapper;
        this.tagService = tagService;
        this.authorNameResolver = authorNameResolver;
        this.packageDependencyParser = packageDependencyParser;
        this.assetDependencyService = assetDependencyService;
        this.downloadEventEnabled = downloadEventEnabled;
        this.maxUploadSizeBytes = maxUploadSizeBytes;
    }

    @Override
    @Transactional
    public Long createDraft(CreateAssetRequest request, Long ownerId) {
        AssetEntity asset = new AssetEntity();
        asset.setOwnerId(ownerId);
        asset.setName(request.name());
        asset.setSummary(request.summary());
        asset.setCategory(StrUtil.blankToDefault(request.category(), "OTHER"));
        asset.setStatus(AssetStatus.DRAFT.name());
        asset.setDeleted(BaseEntity.NOT_DELETED);
        assetMapper.insert(asset);
        log.info("asset draft created, assetId={}, ownerId={}", asset.getId(), ownerId);
        return asset.getId();
    }

    /**
     * 下架（docs/ARCHITECTURE.md §9.4）：仅 Admin，不做「先审后发」，Admin 的审核就是下架。
     *
     * <p>校验顺序是先角色后状态：越权探测不该得知资源是否存在。
     */
    @Override
    @Transactional
    public void suspend(Long assetId, Long operatorId) {
        if (!Role.ADMIN.name().equals(UserContext.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "only admin can suspend an asset");
        }
        AssetEntity asset = accessGuard.findActive(assetId);
        if (!AssetStatus.PUBLISHED.name().equals(asset.getStatus())) {
            throw new BizException(ErrorCode.ASSET_STATUS_INVALID,
                    "only PUBLISHED asset can be suspended, current: " + asset.getStatus());
        }

        long newOpVersion = (asset.getOpVersion() == null ? 0L : asset.getOpVersion()) + 1;
        AssetEntity update = new AssetEntity();
        update.setId(assetId);
        update.setStatus(AssetStatus.SUSPENDED.name());
        update.setOpVersion(newOpVersion);
        assetMapper.updateById(update);

        // 事件体 status 必须是变更后的值：消费端对非 PUBLISHED 一律删文档（ES 索引同步移除）
        asset.setStatus(AssetStatus.SUSPENDED.name());
        asset.setLatestVersion(asset.getLatestVersion());
        AssetVersionEntity latest = assetVersionMapper.selectOne(new LambdaQueryWrapper<AssetVersionEntity>()
                .eq(AssetVersionEntity::getAssetId, assetId)
                .eq(AssetVersionEntity::getVersion, asset.getLatestVersion())
                .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED));
        outboxEventService.appendAssetUploaded(asset, asset.getLatestVersion(),
                latest == null ? null : latest.getReadme(), newOpVersion);
        log.info("asset suspended by admin, assetId={}, operatorId={}, opVersion={}",
                assetId, operatorId, newOpVersion);
    }

    @Override
    public AssetVersionVo uploadVersion(Long assetId, Long currentUserId, byte[] content, String contentType,
                                       String version, String changelog, String readme) {
        AssetEntity asset = requireOwnedAsset(assetId, currentUserId);
        // 支持给已发布资源追加新版本；只有已下架资源不允许再产生新版本
        if (AssetStatus.SUSPENDED.name().equals(asset.getStatus())) {
            throw new BizException(ErrorCode.ASSET_STATUS_INVALID, "suspended asset does not allow new versions");
        }
        // 资源已经公开时，新版本直接可见——本项目不做「先审后发」
        boolean autoPublish = AssetStatus.PUBLISHED.name().equals(asset.getStatus());
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.FILE_EMPTY);
        }
        if (content.length > maxUploadSizeBytes) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "file size " + content.length + " exceeds limit " + maxUploadSizeBytes);
        }

        String resolvedVersion = resolveVersion(asset, version);
        if (existsVersion(assetId, resolvedVersion)) {
            throw new BizException(ErrorCode.ASSET_VERSION_ALREADY_EXISTS, "version already exists: " + resolvedVersion);
        }

        String objectKey = OBJECT_KEY_TEMPLATE.formatted(assetId, resolvedVersion);

        // 先落对象存储，再落库：反过来会产生"库里有点、对象不存在"的坏数据，更难修复
        FileUploadResponse uploaded = uploadToFileService(objectKey, contentType, content);

        AssetVersionVo versionVo;
        try {
            versionVo = assetVersionWriter.write(asset, resolvedVersion, changelog, readme, uploaded, autoPublish);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发上传同一版本：唯一索引兜底，如实回报「版本已存在」，
            // 不要让通用兜底把它变成 90000 system error
            deleteQuietly(objectKey);
            throw new BizException(ErrorCode.ASSET_VERSION_ALREADY_EXISTS,
                    "version already exists (concurrent upload): " + resolvedVersion);
        } catch (RuntimeException e) {
            // 补偿：其他 DB 落库失败时删除已上传对象，避免孤儿文件
            deleteQuietly(objectKey);
            throw e;
        }
        // 依赖解析放在补偿逻辑之外：它只读包内 aihub.yaml、不碰对象存储，
        // 失败也不能反过来删掉刚上传成功的文件（docs/ARCHITECTURE.md §8.4）
        List<String> dependencyNotes = syncDependenciesFromPackage(assetId, content);
        return versionVo.withDependencyNotes(dependencyNotes.isEmpty() ? null : dependencyNotes);
    }

    /**
     * 解析上传包内的 {@code aihub.yaml - requires} 并同步依赖。
     *
     * <p>依赖是增强信息，任何失败都不阻断上传（docs/ARCHITECTURE.md §8.4），问题逐条作为说明返回。
     */
    private List<String> syncDependenciesFromPackage(Long assetId, byte[] content) {
        try {
            com.aihub.asset.dependency.PackageDependencyParser.ParseResult parsed =
                    packageDependencyParser.parse(content);
            List<String> notes = new ArrayList<>(parsed.problems());
            // 无论有没有声明都同步一次：依赖以最新版本的声明为准，
            // 新版本没带依赖声明时旧依赖必须被清掉（全量替换语义）
            notes.addAll(assetDependencyService.syncFromManifest(
                    assetId, parsed.requirements(), parsed.manifestPresent()));
            return notes;
        } catch (Exception e) {
            log.warn("parse package dependencies failed, assetId={}, cause={}", assetId, e.toString());
            return List.of("依赖解析失败（不影响上传）：" + e.getMessage());
        }
    }

    @Override
    public com.aihub.asset.vo.OwnAssetLookupVo lookupOwnByName(Long currentUserId, String name) {
        if (currentUserId == null || name == null || name.isBlank()) {
            return null;
        }
        List<AssetEntity> found = assetMapper.selectList(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getOwnerId, currentUserId)
                .eq(AssetEntity::getName, name.trim())
                .eq(AssetEntity::getDeleted, BaseEntity.NOT_DELETED)
                .orderByDesc(AssetEntity::getId)
                .last("LIMIT 1"));
        if (found.isEmpty()) {
            return null;
        }
        AssetEntity asset = found.get(0);
        List<String> versions = assetVersionMapper.selectList(new LambdaQueryWrapper<AssetVersionEntity>()
                        .eq(AssetVersionEntity::getAssetId, asset.getId())
                        .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream()
                .map(AssetVersionEntity::getVersion)
                .sorted()
                .toList();
        return new com.aihub.asset.vo.OwnAssetLookupVo(asset.getId(), asset.getName(), asset.getStatus(),
                asset.getLatestVersion(), versions);
    }

    @Override
    @Transactional
    public void publish(Long assetId, Long currentUserId) {
        AssetEntity asset = requireOwnedAsset(assetId, currentUserId);
        if (!AssetStatus.DRAFT.name().equals(asset.getStatus())) {
            throw new BizException(ErrorCode.ASSET_STATUS_INVALID, "only DRAFT asset can be published");
        }
        if (StrUtil.isBlank(asset.getLatestVersion())) {
            throw new BizException(ErrorCode.ASSET_STATUS_INVALID, "asset has no version to publish");
        }

        AssetVersionEntity version = findVersion(assetId, asset.getLatestVersion());
        if (version == null) {
            throw new BizException(ErrorCode.ASSET_VERSION_ALREADY_EXISTS, "latest version record missing");
        }

        AssetVersionEntity versionUpdate = new AssetVersionEntity();
        versionUpdate.setId(version.getId());
        versionUpdate.setPublishStatus(VersionPublishStatus.PUBLISHED.name());
        versionUpdate.setPublishedAt(LocalDateTime.now());
        assetVersionMapper.updateById(versionUpdate);

        // 操作版本单调递增：消费端据此丢弃乱序到达的旧事件
        long newOpVersion = (asset.getOpVersion() == null ? 0L : asset.getOpVersion()) + 1;

        AssetEntity assetUpdate = new AssetEntity();
        assetUpdate.setId(assetId);
        assetUpdate.setStatus(AssetStatus.PUBLISHED.name());
        assetUpdate.setOpVersion(newOpVersion);
        assetMapper.updateById(assetUpdate);

        // 事件与状态变更必须在同一事务内落库（OutboxEventService 的 MANDATORY 传播行为强制这一点）。
        // 事件体里的 status 必须是变更之后的值，否则消费端会把已发布资源判为草稿而漏建索引。
        asset.setStatus(AssetStatus.PUBLISHED.name());
        outboxEventService.appendAssetUploaded(asset, version.getVersion(), version.getReadme(), newOpVersion);

        log.info("asset published, assetId={}, version={}, opVersion={}",
                assetId, asset.getLatestVersion(), newOpVersion);
    }

    /**
     * 编辑元数据。
     *
     * <p>名称/简介/分类都进 ES 索引，必须与标签变更走同一条同步路径（bump opVersion + 同事务写
     * outbox），否则搜索结果里的名称会停在旧值。
     */
    @Override
    @Transactional
    public void update(Long assetId, Long currentUserId, UpdateAssetRequest request) {
        AssetEntity asset = requireOwnedAsset(assetId, currentUserId);

        String category = StrUtil.isBlank(request.category()) ? "OTHER" : request.category();
        long newOpVersion = (asset.getOpVersion() == null ? 0L : asset.getOpVersion()) + 1;

        AssetEntity update = new AssetEntity();
        update.setId(assetId);
        update.setName(request.name());
        update.setSummary(request.summary());
        update.setCategory(category);
        update.setOpVersion(newOpVersion);
        assetMapper.updateById(update);

        // 事件体必须是变更之后的值，否则索引里会写回旧名称
        asset.setName(request.name());
        asset.setSummary(request.summary());
        asset.setCategory(category);
        AssetVersionEntity latest = asset.getLatestVersion() == null
                ? null : findVersion(assetId, asset.getLatestVersion());
        outboxEventService.appendAssetUploaded(asset, asset.getLatestVersion(),
                latest == null ? null : latest.getReadme(), newOpVersion);

        log.info("asset updated, assetId={}, ownerId={}, opVersion={}",
                assetId, currentUserId, newOpVersion);
    }

    /**
     * 软删除资源（含版本与文件元数据）。
     *
     * <p>状态同时置 SUSPENDED：事件体的 status 必须是非 PUBLISHED，消费端才会把索引文档删掉
     * （docs/SEARCH.md §2）；只写 deleted 而沿用 PUBLISHED 会让事件把已删除资源重新 upsert 回索引。
     */
    @Override
    @Transactional
    public void delete(Long assetId, Long currentUserId) {
        AssetEntity asset = requireOwnedAsset(assetId, currentUserId);

        long deletedAt = System.currentTimeMillis();
        long newOpVersion = (asset.getOpVersion() == null ? 0L : asset.getOpVersion()) + 1;

        AssetEntity update = new AssetEntity();
        update.setId(assetId);
        update.setDeleted(deletedAt);
        update.setStatus(AssetStatus.SUSPENDED.name());
        update.setOpVersion(newOpVersion);
        assetMapper.updateById(update);

        // 版本与文件元数据一并软删，避免残留行被其他查询捞出来
        AssetVersionEntity versionSoftDelete = new AssetVersionEntity();
        versionSoftDelete.setDeleted(deletedAt);
        assetVersionMapper.update(versionSoftDelete, new LambdaQueryWrapper<AssetVersionEntity>()
                .eq(AssetVersionEntity::getAssetId, assetId)
                .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED));

        AssetFileEntity fileSoftDelete = new AssetFileEntity();
        fileSoftDelete.setDeleted(deletedAt);
        assetFileMapper.update(fileSoftDelete, new LambdaQueryWrapper<AssetFileEntity>()
                .eq(AssetFileEntity::getAssetId, assetId)
                .eq(AssetFileEntity::getDeleted, BaseEntity.NOT_DELETED));

        asset.setStatus(AssetStatus.SUSPENDED.name());
        AssetVersionEntity latest = asset.getLatestVersion() == null
                ? null : findVersion(assetId, asset.getLatestVersion());
        outboxEventService.appendAssetUploaded(asset, asset.getLatestVersion(),
                latest == null ? null : latest.getReadme(), newOpVersion);

        log.info("asset deleted, assetId={}, ownerId={}", assetId, currentUserId);
    }

    /** 批量删除版本的条数上限：防御性限制，防止一次请求拖出过长事务 */
    static final int MAX_VERSION_DELETE_BATCH = 20;

    @Override
    @Transactional
    public String deleteVersions(Long assetId, List<String> versions, Long currentUserId) {
        AssetEntity asset = requireOwnedAsset(assetId, currentUserId);
        List<String> requested = versions == null ? List.of() : versions.stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
        if (requested.isEmpty() || requested.size() > MAX_VERSION_DELETE_BATCH) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "versions must contain 1.." + MAX_VERSION_DELETE_BATCH + " entries");
        }

        List<AssetVersionEntity> live = assetVersionMapper.selectList(
                new LambdaQueryWrapper<AssetVersionEntity>()
                        .eq(AssetVersionEntity::getAssetId, assetId)
                        .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED));
        Map<String, AssetVersionEntity> liveByVersion = live.stream()
                .collect(Collectors.toMap(AssetVersionEntity::getVersion, Function.identity(), (a, b) -> a));
        // 幂等：请求里不存在的版本直接跳过
        List<AssetVersionEntity> matched = requested.stream()
                .map(liveByVersion::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (matched.isEmpty()) {
            // 没有实际删除任何东西 → 不变更、不发事件
            return asset.getLatestVersion();
        }
        List<AssetVersionEntity> remaining = live.stream()
                .filter(v -> !matched.contains(v))
                .toList();
        if (remaining.isEmpty() && AssetStatus.PUBLISHED.name().equals(asset.getStatus())) {
            // "已发布且无版本"是非法态：应删整个资源而不是删光版本
            throw new BizException(ErrorCode.ASSET_STATUS_INVALID,
                    "cannot delete the last version of a published asset; delete the asset instead");
        }

        long deletedAt = System.currentTimeMillis();
        long newOpVersion = (asset.getOpVersion() == null ? 0L : asset.getOpVersion()) + 1;

        AssetVersionEntity versionSoftDelete = new AssetVersionEntity();
        versionSoftDelete.setDeleted(deletedAt);
        assetVersionMapper.update(versionSoftDelete, new LambdaQueryWrapper<AssetVersionEntity>()
                .eq(AssetVersionEntity::getAssetId, assetId)
                .in(AssetVersionEntity::getVersion, matched.stream().map(AssetVersionEntity::getVersion).toList())
                .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED));

        List<Long> versionIds = matched.stream().map(AssetVersionEntity::getId).toList();
        AssetFileEntity fileSoftDelete = new AssetFileEntity();
        fileSoftDelete.setDeleted(deletedAt);
        assetFileMapper.update(fileSoftDelete, new LambdaQueryWrapper<AssetFileEntity>()
                .in(AssetFileEntity::getVersionId, versionIds)
                .eq(AssetFileEntity::getDeleted, BaseEntity.NOT_DELETED));

        // 重算 latest_version：取剩余版本中语义版本号最大者；删光则置空（DRAFT 回到无版本草稿态）。
        // 不沿用列表的 createTime 排序——"后传的 1.0.0"不该压过"先传的 2.0.0"
        String newLatest = remaining.stream()
                .map(AssetVersionEntity::getVersion)
                .max(AssetServiceImpl::compareSemanticVersion)
                .orElse(null);

        // 置空 latest 必须用 UpdateWrapper 显式 set：
        // updateById 默认策略会忽略 null 字段，"删光版本"的置空根本写不进库
        assetMapper.update(null, new LambdaUpdateWrapper<AssetEntity>()
                .eq(AssetEntity::getId, assetId)
                .set(AssetEntity::getLatestVersion, newLatest)
                .set(AssetEntity::getOpVersion, newOpVersion));

        // 事件体取重算后 latest 的 readme；opVersion 递增，否则防乱序策略会拒收
        asset.setLatestVersion(newLatest);
        AssetVersionEntity latestEntity = newLatest == null ? null : liveByVersion.get(newLatest);
        outboxEventService.appendAssetUploaded(asset, newLatest,
                latestEntity == null ? null : latestEntity.getReadme(), newOpVersion);

        log.info("asset versions deleted, assetId={}, versions={}, newLatest={}, opVersion={}",
                assetId, matched.stream().map(AssetVersionEntity::getVersion).toList(), newLatest, newOpVersion);
        return newLatest;
    }

    /** 语义版本比较：按 "." 分段数值比较，缺段补 0；含非数字段时退化到字符串比较。 */
    static int compareSemanticVersion(String a, String b) {
        long[] pa = parseVersionSegments(a);
        long[] pb = parseVersionSegments(b);
        if (pa.length > 0 && pb.length > 0) {
            int len = Math.max(pa.length, pb.length);
            for (int i = 0; i < len; i++) {
                long x = i < pa.length ? pa[i] : 0L;
                long y = i < pb.length ? pb[i] : 0L;
                if (x != y) {
                    return Long.compare(x, y);
                }
            }
        }
        return a.compareTo(b);
    }

    private static long[] parseVersionSegments(String version) {
        String[] parts = version.split("\\.");
        long[] segments = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                segments[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException e) {
                // 任一段非数字即放弃数值比较
                return new long[0];
            }
        }
        return segments;
    }

    @Override
    public PageResult<AssetVo> list(PageQuery query, String category, Long currentUserId, Boolean onlyMine) {
        Page<AssetEntity> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        LambdaQueryWrapper<AssetEntity> wrapper = new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getDeleted, BaseEntity.NOT_DELETED);
        if (StrUtil.isNotBlank(category)) {
            wrapper.eq(AssetEntity::getCategory, category);
        }
        if (Boolean.TRUE.equals(onlyMine)) {
            // 「我的资源」：只看自己名下的全部状态（草稿 + 已发布 + 已下架）。
            // 匿名调用时「我的」不成立，返回空页而不是悄悄退化成全站列表。
            if (currentUserId == null) {
                return PageResult.of(List.of(), 0L, query.pageOrDefault(), query.sizeOrDefault());
            }
            wrapper.eq(AssetEntity::getOwnerId, currentUserId);
        } else {
            // 可见性：已发布对所有人可见；草稿只对 owner 可见（docs/ARCHITECTURE.md §9.4）
            if (currentUserId == null) {
                wrapper.eq(AssetEntity::getStatus, AssetStatus.PUBLISHED.name());
            } else {
                wrapper.and(condition -> condition
                        .eq(AssetEntity::getStatus, AssetStatus.PUBLISHED.name())
                        .or()
                        .eq(AssetEntity::getOwnerId, currentUserId));
            }
        }
        // 按更新时间倒序：发布的资源会改状态、追加新版本会改 latest_version，
        // 用创建时间排会让"刚更新过的资源"沉在列表底部。update_time 只到秒，用 id 兜底
        wrapper.orderByDesc(AssetEntity::getUpdateTime).orderByDesc(AssetEntity::getId);

        Page<AssetEntity> result = assetMapper.selectPage(page, wrapper);
        // 标签批量装配（每页一次查询，避免逐行 N+1）
        Map<Long, List<String>> tagsByAsset = tagService.tagsOfAssets(
                result.getRecords().stream().map(AssetEntity::getId).toList());
        // 作者昵称同样批量解析；User 服务抖动时解析器内部降级为空 Map，前端用"用户#后6位"兜底
        Map<Long, String> ownerNames = authorNameResolver.resolve(
                result.getRecords().stream().map(AssetEntity::getOwnerId).toList());
        return PageResult.of(
                result.getRecords().stream()
                        .map(a -> toVo(a, tagsByAsset.get(a.getId()), ownerNames.get(a.getOwnerId()))).toList(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize());
    }

    @Override
    public AssetDetailVo detail(Long assetId, Long currentUserId) {
        AssetEntity asset = requireVisibleAsset(assetId, currentUserId);
        AssetVersionVo versionVo = null;
        if (StrUtil.isNotBlank(asset.getLatestVersion())) {
            AssetVersionEntity version = findVersion(assetId, asset.getLatestVersion());
            if (version != null) {
                versionVo = toVersionVo(assetId, version);
            }
        }
        return new AssetDetailVo(asset.getId(), asset.getOwnerId(), asset.getName(), asset.getSummary(),
                asset.getCategory(), asset.getStatus(), asset.getLatestVersion(),
                asset.getCreateTime(), asset.getUpdateTime(), versionVo);
    }

    @Override
    public List<AssetVersionVo> listVersions(Long assetId, Long currentUserId) {
        requireVisibleAsset(assetId, currentUserId);

        List<AssetVersionEntity> versions = assetVersionMapper.selectList(
                new LambdaQueryWrapper<AssetVersionEntity>()
                        .eq(AssetVersionEntity::getAssetId, assetId)
                        .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED)
                        // create_time 只到秒，同一秒内建的多个版本顺序不确定，
                        // 补 id 兜底让顺序稳定
                        .orderByDesc(AssetVersionEntity::getCreateTime)
                        .orderByDesc(AssetVersionEntity::getId));
        if (versions.isEmpty()) {
            return List.of();
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

        return versions.stream()
                .map(version -> toVersionVo(version, files.get(version.getId())))
                .toList();
    }

    @Override
    public AssetDownload download(Long assetId, Long currentUserId) {
        AssetEntity asset = requireVisibleAsset(assetId, currentUserId);
        if (StrUtil.isBlank(asset.getLatestVersion())) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "asset has no downloadable version");
        }
        AssetVersionEntity version = findVersion(assetId, asset.getLatestVersion());
        if (version == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return doDownload(asset, version, currentUserId);
    }

    @Override
    public AssetDownload downloadVersion(Long assetId, String version, Long currentUserId) {
        AssetEntity asset = requireVisibleAsset(assetId, currentUserId);
        AssetVersionEntity target = findVersion(assetId, version);
        if (target == null) {
            throw new BizException(ErrorCode.ASSET_VERSION_NOT_FOUND, "version not found: " + version);
        }
        return doDownload(asset, target, currentUserId);
    }

    /**
     * 下载指定版本的 README。
     *
     * <p>README 存在 DB 里（文本），这里只做「取出来 + 起个文件名的活儿」，不经过 File Service。
     * 可见性校验与 zip 下载共用 {@code requireVisibleAsset}，避免两处规则漂移。
     */
    @Override
    public AssetDownload downloadReadme(Long assetId, String version, Long currentUserId) {
        AssetEntity asset = requireVisibleAsset(assetId, currentUserId);
        AssetVersionEntity target = findVersion(assetId, version);
        if (target == null) {
            throw new BizException(ErrorCode.ASSET_VERSION_NOT_FOUND, "version not found: " + version);
        }
        if (StrUtil.isBlank(target.getReadme())) {
            // 该版本没写 README：不存在的东西不假造一个空文件给用户
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "version " + version + " has no README");
        }
        String fileName = "%s-%s-README.md".formatted(safeFileNamePart(asset.getName()), version);
        return new AssetDownload(fileName, target.getReadme().getBytes(StandardCharsets.UTF_8));
    }

    /** 下载指定版本，归属与可见性已在调用前校验。 */
    private AssetDownload doDownload(AssetEntity asset, AssetVersionEntity version, Long currentUserId) {
        AssetFileEntity file = assetFileMapper.selectOne(new LambdaQueryWrapper<AssetFileEntity>()
                .eq(AssetFileEntity::getVersionId, version.getId())
                .eq(AssetFileEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (file == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }

        ResponseEntity<byte[]> response = fileApi.download(file.getObjectKey());
        byte[] content = response.getBody();
        if (content == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "empty response body from file service");
        }
        // 下载完成事件（docs/ARCHITECTURE.md §8.2）：统计走 MQ 异步，失败只记录不抛出，
        // 统计是非关键路径，不能影响下载本身。
        emitDownloadCompleted(asset, version, currentUserId);
        log.info("asset version downloaded, assetId={}, version={}, userId={}",
                asset.getId(), version.getVersion(), UserContext.getUserId());
        return new AssetDownload(fileNameOf(file.getObjectKey()), content);
    }

    /* ------------------------------------------------------------------ 内部方法 */

    /**
     * 投递「下载完成」事件。
     *
     * <p>不走 outbox：下载统计是非关键路径且允许少量丢失，为它落一张消息表反而把关键路径变重。
     * 失败只打 WARN，丢一条事件的代价是热门榜少 1 分。
     */
    private void emitDownloadCompleted(AssetEntity asset, AssetVersionEntity version, Long currentUserId) {
        if (!downloadEventEnabled) {
            return;
        }
        EventDispatcher dispatcher = downloadEventDispatcher.getIfAvailable();
        if (dispatcher == null) {
            // 未启用 MQ（outbox 关闭）时没有投递通道，同样只允许丢失
            return;
        }
        DownloadCompletedEvent event = new DownloadCompletedEvent(
                UUID.randomUUID().toString().replace("-", ""),
                EventTopics.CURRENT_EVENT_VERSION,
                currentUserId == null ? 0L : currentUserId,
                asset.getId(),
                version.getVersion(),
                System.currentTimeMillis());
        try {
            dispatcher.dispatch(new OutboxMessage(event.eventId(), EventTopics.DOWNLOAD_TOPIC,
                    EventTopics.DOWNLOAD_COMPLETED, objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.warn("download event dispatch failed, assetId={}, version={}: {}",
                    asset.getId(), version.getVersion(), e.getMessage());
        }
    }

    private FileUploadResponse uploadToFileService(String objectKey, String contentType, byte[] content) {
        try {
            var result = fileApi.upload(objectKey, contentType, content);
            if (result.code() != ErrorCode.SUCCESS.getCode()) {
                // 下游业务失败走 HTTP 200 + 非 0 code，必须显式判断，否则会把失败当成功
                throw new BizException(ErrorCode.FILE_UPLOAD_FAILED, "file service rejected: " + result.message());
            }
            return result.data();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("upload to file service failed, objectKey={}", objectKey, e);
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    private void deleteQuietly(String objectKey) {
        try {
            fileApi.delete(objectKey);
        } catch (Exception e) {
            // 补偿失败只能记录：此时数据库已回滚，对象成为孤儿，需要靠后续对账清理
            log.error("compensating delete failed, objectKey={}, orphan object remains", objectKey, e);
        }
    }

    private AssetEntity requireOwnedAsset(Long assetId, Long currentUserId) {
        return accessGuard.requireOwned(assetId, currentUserId);
    }

    private AssetEntity requireVisibleAsset(Long assetId, Long currentUserId) {
        return accessGuard.requireVisible(assetId, currentUserId);
    }

    private AssetEntity findActiveAsset(Long assetId) {
        return accessGuard.findActive(assetId);
    }

    /**
     * 解析本次上传要用的版本号。
     *
     * <p>调用方留空时自动递增：取资源当前最新版本的补丁号 +1（1.0.0 → 1.0.1）；
     * 资源还没有任何版本时用 {@link #DEFAULT_VERSION}。最新版本不是
     * {@code major.minor.patch}（如 "v1"、"2026-09"）时明确报错，要求调用方显式指定：
     * 猜一个版本号会让用户拿到自己没预期的版本，比报错更糟。
     */
    private String resolveVersion(AssetEntity asset, String requested) {
        if (StrUtil.isNotBlank(requested)) {
            return requested.trim();
        }
        String latest = asset.getLatestVersion();
        if (StrUtil.isBlank(latest)) {
            return DEFAULT_VERSION;
        }
        Matcher matcher = AUTO_INCREMENTABLE_VERSION.matcher(latest.trim());
        if (!matcher.matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "cannot auto-increment version \"" + latest + "\", please specify version explicitly");
        }
        long patch = Long.parseLong(matcher.group(3)) + 1;
        return "%s.%s.%d".formatted(matcher.group(1), matcher.group(2), patch);
    }

    private boolean existsVersion(Long assetId, String version) {
        return findVersion(assetId, version) != null;
    }

    private AssetVersionEntity findVersion(Long assetId, String version) {
        return assetVersionMapper.selectOne(new LambdaQueryWrapper<AssetVersionEntity>()
                .eq(AssetVersionEntity::getAssetId, assetId)
                .eq(AssetVersionEntity::getVersion, version)
                .eq(AssetVersionEntity::getDeleted, BaseEntity.NOT_DELETED));
    }

    private AssetVersionVo toVersionVo(Long assetId, AssetVersionEntity version) {
        AssetFileEntity file = assetFileMapper.selectOne(new LambdaQueryWrapper<AssetFileEntity>()
                .eq(AssetFileEntity::getVersionId, version.getId())
                .eq(AssetFileEntity::getDeleted, BaseEntity.NOT_DELETED));
        return toVersionVo(version, file);
    }

    private AssetVersionVo toVersionVo(AssetVersionEntity version, AssetFileEntity file) {
        return new AssetVersionVo(version.getId(), version.getVersion(), version.getChangelog(), version.getReadme(),
                version.getPublishStatus(),
                file == null ? null : file.getChecksum(),
                file == null ? null : file.getSize(),
                file == null ? null : file.getContentType(),
                null);
    }

    private AssetVo toVo(AssetEntity asset, List<String> tags, String ownerName) {
        return new AssetVo(asset.getId(), asset.getOwnerId(), ownerName, asset.getName(), asset.getSummary(),
                asset.getCategory(), asset.getStatus(), asset.getLatestVersion(),
                tags == null ? List.of() : tags, asset.getCreateTime(), asset.getUpdateTime());
    }

    private String fileNameOf(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        return slash >= 0 ? objectKey.substring(slash + 1) : objectKey;
    }

    /**
     * 把资源名整理成可安全用作下载文件名的片段。
     *
     * <p>资源名是用户输入，可能带路径分隔符或 Windows 非法字符
     * （{@code \ / : * ? " < > |}），直接拼进文件名会有落盘问题，统一替换掉。
     */
    private String safeFileNamePart(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "asset";
        }
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|\r\n\t]", "_").trim();
        if (cleaned.isEmpty()) {
            return "asset";
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }
}
