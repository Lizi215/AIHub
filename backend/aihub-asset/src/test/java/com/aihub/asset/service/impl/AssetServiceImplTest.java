package com.aihub.asset.service.impl;

import com.aihub.api.dto.FileUploadResponse;
import com.aihub.api.event.EventTopics;
import com.aihub.api.file.FileApi;
import com.aihub.asset.dto.CreateAssetRequest;
import com.aihub.asset.dto.UpdateAssetRequest;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.entity.AssetFileEntity;
import com.aihub.asset.entity.AssetVersionEntity;
import com.aihub.asset.entity.AssetTagEntity;
import com.aihub.asset.entity.TagEntity;
import com.aihub.asset.enums.AssetStatus;
import com.aihub.asset.enums.VersionPublishStatus;
import com.aihub.asset.mapper.AssetFileMapper;
import com.aihub.asset.mapper.AssetMapper;
import com.aihub.asset.mapper.AssetTagMapper;
import com.aihub.asset.mapper.AssetVersionMapper;
import com.aihub.asset.mapper.TagMapper;
import com.aihub.asset.service.AssetAccessGuard;
import com.aihub.asset.service.OutboxEventService;
import com.aihub.asset.vo.AssetDetailVo;
import com.aihub.asset.vo.AssetDownload;
import com.aihub.asset.vo.AssetVersionVo;
import com.aihub.common.auth.Role;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.common.trace.UserContext;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 资源服务业务规则。
 *
 * <p>重点是越权校验：非 owner 不能上传、发布，也不能查看草稿详情或下载。
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceImplTest {

    private static final long OWNER_ID = 1001L;
    private static final long OTHER_ID = 2002L;
    private static final long USER_ID = 3003L;
    private static final long ASSET_ID = 1945678901234567890L;
    private static final long MAX_SIZE = 1024L;

    @Mock
    private AssetMapper assetMapper;
    @Mock
    private AssetVersionMapper assetVersionMapper;
    @Mock
    private AssetFileMapper assetFileMapper;
    @Mock
    private AssetVersionWriter assetVersionWriter;
    @Mock
    private FileApi fileApi;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private org.springframework.beans.factory.ObjectProvider<com.aihub.common.outbox.EventDispatcher>
            downloadEventDispatcher;
    /** 真实实例：payload 序列化被 mock 的话会返回 null，后续断言全部失真 */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private AssetServiceImpl assetService;

    @BeforeAll
    static void initTableInfo() {
        // 断言捕获到的 wrapper 会调 getSqlSegment()，它依赖 Lambda 列名缓存；
        // 纯 Mockito 单测（无 Spring/MyBatis 上下文）必须显式初始化一次
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AssetEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 标签服务走真实 lambda wrapper，需要实体的 TableInfo 缓存（与 AssetQueryServiceImplTest 同法）
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, AssetTagEntity.class);
        TableInfoHelper.initTableInfo(assistant, TagEntity.class);
        // 标签服务用真实实现 + mock mapper：列表装配标签时返回空（不影响既有断言）
        TagMapper tagMapper = mock(TagMapper.class);
        AssetTagMapper assetTagMapper = mock(AssetTagMapper.class);
        TagServiceImpl tagService = new TagServiceImpl(tagMapper, assetTagMapper,
                new AssetAccessGuard(assetMapper), assetMapper, assetVersionMapper, outboxEventService);
        assetService = new AssetServiceImpl(assetMapper, assetVersionMapper, assetFileMapper,
                assetVersionWriter, new AssetAccessGuard(assetMapper), outboxEventService, fileApi,
                downloadEventDispatcher, objectMapper, tagService,
                mock(com.aihub.asset.support.AuthorNameResolver.class),
                mock(com.aihub.asset.dependency.PackageDependencyParser.class),
                mock(com.aihub.asset.service.AssetDependencyService.class), MAX_SIZE, true);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    /* ------------------------------------------------------------ 创建草稿 */

    @Test
    @DisplayName("创建草稿：归属为当前用户、状态 DRAFT、分类缺省为 OTHER")
    void createDraftShouldSetOwnerAndDraftStatus() {
        assetService.createDraft(new CreateAssetRequest("My Skill", "demo", null), OWNER_ID);

        ArgumentCaptor<AssetEntity> captor = ArgumentCaptor.forClass(AssetEntity.class);
        verify(assetMapper).insert(captor.capture());
        AssetEntity saved = captor.getValue();

        assertThat(saved.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(saved.getStatus()).isEqualTo(AssetStatus.DRAFT.name());
        assertThat(saved.getCategory()).isEqualTo("OTHER");
        assertThat(saved.getDeleted()).isZero();
    }

    /* ------------------------------------------------------------ 上传：越权与状态 */

    @Nested
    @DisplayName("上传阶段②")
    class UploadVersion {

        @Test
        @DisplayName("非 owner 上传被拒绝，且不得先把文件传到对象存储")
        void shouldRejectUploadByNonOwnerBeforeTouchingStorage() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);

            assertThatThrownBy(() -> assetService.uploadVersion(ASSET_ID, OTHER_ID, zip(), null, null, null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);

            // 关键：授权失败必须在产生副作用之前，否则会留下孤儿对象
            verifyNoInteractions(fileApi);
            verifyNoInteractions(assetVersionWriter);
        }

        @Test
        @DisplayName("已下架资源不允许再产生新版本")
        void shouldRejectUploadWhenAssetSuspended() {
            mockAsset(OWNER_ID, AssetStatus.SUSPENDED);

            assertThatThrownBy(() -> assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, null, null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_STATUS_INVALID);
        }

        @Test
        @DisplayName("已发布资源可以追加新版本，且新版本直接发布（不做先审后发）")
        void shouldAutoPublishNewVersionOfPublishedAsset() {
            mockAsset(OWNER_ID, AssetStatus.PUBLISHED);
            when(assetVersionMapper.selectOne(any())).thenReturn(null);
            when(fileApi.upload(anyString(), any(), any())).thenReturn(uploadOk());
            when(assetVersionWriter.write(any(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenReturn(new AssetVersionVo(2L, "1.1.0", null, null,
                            VersionPublishStatus.PUBLISHED.name(), "abc", 21L, "application/zip", null));

            assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, "1.1.0", null, null);

            ArgumentCaptor<Boolean> autoPublish = ArgumentCaptor.forClass(Boolean.class);
            verify(assetVersionWriter).write(any(), anyString(), any(), any(), any(), autoPublish.capture());
            assertThat(autoPublish.getValue()).isTrue();
        }

        @Test
        @DisplayName("空文件被拒绝")
        void shouldRejectEmptyContent() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);

            assertThatThrownBy(() -> assetService.uploadVersion(ASSET_ID, OWNER_ID, new byte[0], null, null, null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FILE_EMPTY);
        }

        @Test
        @DisplayName("超过上限的文件被拒绝（中转模式会把字节驻留堆内存）")
        void shouldRejectOversizedContent() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);

            assertThatThrownBy(() -> assetService.uploadVersion(ASSET_ID, OWNER_ID, new byte[(int) MAX_SIZE + 1],
                    null, null, null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FILE_TOO_LARGE);
        }

        @Test
        @DisplayName("同一资源内重复版本号被拒绝，且不触发上传")
        void shouldRejectDuplicateVersion() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);
            when(assetVersionMapper.selectOne(any())).thenReturn(new AssetVersionEntity());

            assertThatThrownBy(() -> assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, "1.0.0", null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_VERSION_ALREADY_EXISTS);

            verifyNoInteractions(fileApi);
        }

        @Test
        @DisplayName("成功上传：对象路径为 assets/{assetId}/{version}/package.zip，且落库交给独立事务")
        void shouldUploadWithContractObjectKey() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);
            when(assetVersionMapper.selectOne(any())).thenReturn(null);
            when(fileApi.upload(anyString(), any(), any())).thenReturn(uploadOk());
            when(assetVersionWriter.write(any(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenReturn(new AssetVersionVo(1L, "1.0.0", null, null,
                            VersionPublishStatus.DRAFT.name(), "abc", 21L, "application/zip", null));

            AssetVersionVo vo = assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), "application/zip",
                    "1.0.0", "log", "readme");

            ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
            verify(fileApi).upload(objectKey.capture(), eq("application/zip"), any());
            assertThat(objectKey.getValue())
                    .isEqualTo("assets/" + ASSET_ID + "/1.0.0/package.zip");
            assertThat(vo.version()).isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("版本号缺省为 1.0.0")
        void shouldDefaultVersionTo100() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);
            when(assetVersionMapper.selectOne(any())).thenReturn(null);
            when(fileApi.upload(anyString(), any(), any())).thenReturn(uploadOk());
            when(assetVersionWriter.write(any(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenReturn(new AssetVersionVo(1L, "1.0.0", null, null, null, null, null, null, null));

            assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, null, null, null);

            ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
            verify(fileApi).upload(objectKey.capture(), any(), any());
            assertThat(objectKey.getValue()).contains("/1.0.0/");
        }

        @Test
        @DisplayName("版本号留空且资源已有最新版本时，补丁号自动 +1")
        void shouldAutoIncrementPatchWhenVersionBlank() {
            mockAssetWithLatestVersion(OWNER_ID, AssetStatus.DRAFT, "1.0.0");
            when(assetVersionMapper.selectOne(any())).thenReturn(null);
            when(fileApi.upload(anyString(), any(), any())).thenReturn(uploadOk());
            when(assetVersionWriter.write(any(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenReturn(new AssetVersionVo(2L, "1.0.1", null, null, null, null, null, null, null));

            assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, null, null, null);

            // 留空必须解析成 1.0.1，而不是恒为 1.0.0（后者第二次上传会撞唯一索引）
            ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
            verify(fileApi).upload(objectKey.capture(), any(), any());
            assertThat(objectKey.getValue()).isEqualTo("assets/" + ASSET_ID + "/1.0.1/package.zip");
        }

        @Test
        @DisplayName("留空时按补丁号递增，不影响 major/minor")
        void shouldAutoIncrementOnlyPatchSegment() {
            mockAssetWithLatestVersion(OWNER_ID, AssetStatus.PUBLISHED, "2.13.7");
            when(assetVersionMapper.selectOne(any())).thenReturn(null);
            when(fileApi.upload(anyString(), any(), any())).thenReturn(uploadOk());
            when(assetVersionWriter.write(any(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenReturn(new AssetVersionVo(3L, "2.13.8", null, null, null, null, null, null, null));

            assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, "", null, null);

            ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
            verify(fileApi).upload(objectKey.capture(), any(), any());
            assertThat(objectKey.getValue()).contains("/2.13.8/");
        }

        @Test
        @DisplayName("最新版本不是 major.minor.patch 时留空明确报错，不猜版本号")
        void shouldRejectAutoIncrementForNonStandardVersion() {
            mockAssetWithLatestVersion(OWNER_ID, AssetStatus.DRAFT, "v1");

            assertThatThrownBy(() -> assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, null, null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PARAM_INVALID);

            verifyNoInteractions(fileApi);
        }

        @Test
        @DisplayName("显式指定版本号时不做任何递增")
        void shouldKeepExplicitVersion() {
            mockAssetWithLatestVersion(OWNER_ID, AssetStatus.DRAFT, "1.0.0");
            when(assetVersionMapper.selectOne(any())).thenReturn(null);
            when(fileApi.upload(anyString(), any(), any())).thenReturn(uploadOk());
            when(assetVersionWriter.write(any(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenReturn(new AssetVersionVo(4L, "3.0.0", null, null, null, null, null, null, null));

            assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, "3.0.0", null, null);

            ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
            verify(fileApi).upload(objectKey.capture(), any(), any());
            assertThat(objectKey.getValue()).contains("/3.0.0/");
        }

        @Test
        @DisplayName("File Service 返回非 0 code 时必须当失败处理（HTTP 200 + 业务码，不能只看状态码）")
        void shouldFailWhenFileServiceReturnsBusinessError() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);
            when(assetVersionMapper.selectOne(any())).thenReturn(null);
            when(fileApi.upload(anyString(), any(), any()))
                    .thenReturn(Result.fail(ErrorCode.FILE_TYPE_NOT_ALLOWED));

            assertThatThrownBy(() -> assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, null, null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FILE_UPLOAD_FAILED);

            verifyNoInteractions(assetVersionWriter);
        }

        @Test
        @DisplayName("并发竞态：落库撞唯一索引时转为 20004，并补偿删除已上传对象（不留孤儿文件）")
        void shouldCompensateWhenPersistFails() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);
            when(assetVersionMapper.selectOne(any())).thenReturn(null);
            when(fileApi.upload(anyString(), any(), any())).thenReturn(uploadOk());
            // 先查不存在（selectOne=null）→ 上传 → 落库时被唯一索引拦下：这正是并发同版本上传的窗口
            when(assetVersionWriter.write(any(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));

            assertThatThrownBy(() -> assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, null, null, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_VERSION_ALREADY_EXISTS);

            verify(fileApi).delete("assets/" + ASSET_ID + "/1.0.0/package.zip");
        }

        @Test
        @DisplayName("上传成功时不触发补偿删除")
        void shouldNotCompensateOnSuccess() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);
            when(assetVersionMapper.selectOne(any())).thenReturn(null);
            when(fileApi.upload(anyString(), any(), any())).thenReturn(uploadOk());
            when(assetVersionWriter.write(any(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenReturn(new AssetVersionVo(1L, "1.0.0", null, null, null, null, null, null, null));

            assetService.uploadVersion(ASSET_ID, OWNER_ID, zip(), null, null, null, null);

            verify(fileApi, never()).delete(anyString());
        }
    }

    /* ------------------------------------------------------------ 发布 */

    @Nested
    @DisplayName("上传阶段③：发布")
    class Publish {

        @Test
        @DisplayName("非 owner 发布被拒绝")
        void shouldRejectPublishByNonOwner() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);

            assertThatThrownBy(() -> assetService.publish(ASSET_ID, OTHER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);

            // BaseMapper 的 updateById 同时有 (T) 与 (Collection<T>) 重载，必须指明类型
            verify(assetMapper, never()).updateById(any(AssetEntity.class));
        }

        @Test
        @DisplayName("非草稿状态不能再次发布")
        void shouldRejectPublishWhenAlreadyPublished() {
            mockAsset(OWNER_ID, AssetStatus.PUBLISHED);

            assertThatThrownBy(() -> assetService.publish(ASSET_ID, OWNER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_STATUS_INVALID);
        }

        @Test
        @DisplayName("没有版本就发布被拒绝")
        void shouldRejectPublishWithoutVersion() {
            AssetEntity asset = asset(OWNER_ID, AssetStatus.DRAFT);
            asset.setLatestVersion(null);
            when(assetMapper.selectOne(any())).thenReturn(asset);

            assertThatThrownBy(() -> assetService.publish(ASSET_ID, OWNER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_STATUS_INVALID);
        }

        @Test
        @DisplayName("发布成功：版本与资源状态一起推进")
        void shouldPublishVersionAndAsset() {
            AssetEntity asset = asset(OWNER_ID, AssetStatus.DRAFT);
            asset.setLatestVersion("1.0.0");
            when(assetMapper.selectOne(any())).thenReturn(asset);
            AssetVersionEntity version = new AssetVersionEntity();
            version.setId(7L);
            when(assetVersionMapper.selectOne(any())).thenReturn(version);

            assetService.publish(ASSET_ID, OWNER_ID);

            ArgumentCaptor<AssetVersionEntity> versionCaptor = ArgumentCaptor.forClass(AssetVersionEntity.class);
            verify(assetVersionMapper).updateById(versionCaptor.capture());
            assertThat(versionCaptor.getValue().getPublishStatus())
                    .isEqualTo(VersionPublishStatus.PUBLISHED.name());
            assertThat(versionCaptor.getValue().getPublishedAt()).isNotNull();

            ArgumentCaptor<AssetEntity> assetCaptor = ArgumentCaptor.forClass(AssetEntity.class);
            verify(assetMapper).updateById(assetCaptor.capture());
            assertThat(assetCaptor.getValue().getStatus()).isEqualTo(AssetStatus.PUBLISHED.name());
        }

        @Test
        @DisplayName("发布时写入 outbox 事件：opVersion 递增，事件里带的是变更后的状态")
        void shouldAppendOutboxEventOnPublish() {
            AssetEntity asset = asset(OWNER_ID, AssetStatus.DRAFT);
            asset.setLatestVersion("1.0.0");
            asset.setOpVersion(3L);
            when(assetMapper.selectOne(any())).thenReturn(asset);

            AssetVersionEntity version = new AssetVersionEntity();
            version.setId(7L);
            version.setVersion("1.0.0");
            version.setReadme("readme-body");
            when(assetVersionMapper.selectOne(any())).thenReturn(version);

            assetService.publish(ASSET_ID, OWNER_ID);

            ArgumentCaptor<AssetEntity> eventAsset = ArgumentCaptor.forClass(AssetEntity.class);
            ArgumentCaptor<Long> opVersion = ArgumentCaptor.forClass(Long.class);
            verify(outboxEventService).appendAssetUploaded(
                    eventAsset.capture(), eq("1.0.0"), eq("readme-body"), opVersion.capture());

            // 已应用版本 3 → 本次必须递增为 4，否则消费端会按"重复"丢弃这条事件
            assertThat(opVersion.getValue()).isEqualTo(4L);
            // 事件体里的状态必须是变更后的值，否则 Search Service 会把已发布资源判为草稿而漏建索引
            assertThat(eventAsset.getValue().getStatus()).isEqualTo(AssetStatus.PUBLISHED.name());
        }
    }

    /* ------------------------------------------------------------ 可见性（越权核心） */

    @Nested
    @DisplayName("详情可见性")
    class Detail {

        @Test
        @DisplayName("已发布资源：匿名可见")
        void publishedIsVisibleToAnonymous() {
            mockAsset(OWNER_ID, AssetStatus.PUBLISHED);

            AssetDetailVo detail = assetService.detail(ASSET_ID, null);

            assertThat(detail.status()).isEqualTo(AssetStatus.PUBLISHED.name());
        }

        @Test
        @DisplayName("草稿：owner 可见")
        void draftIsVisibleToOwner() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);

            assertThat(assetService.detail(ASSET_ID, OWNER_ID).status()).isEqualTo(AssetStatus.DRAFT.name());
        }

        @Test
        @DisplayName("草稿：非 owner 被拒绝（越权）")
        void draftIsHiddenFromOtherUser() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);

            assertThatThrownBy(() -> assetService.detail(ASSET_ID, OTHER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);
        }

        @Test
        @DisplayName("草稿：匿名被拒绝（不出现在公开视图）")
        void draftIsHiddenFromAnonymous() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);

            assertThatThrownBy(() -> assetService.detail(ASSET_ID, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);
        }

        @Test
        @DisplayName("草稿：Maintainer 可跨归属查看（走角色而非归属）")
        void draftIsVisibleToMaintainer() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);
            UserContext.set(OTHER_ID, Role.MAINTAINER.name());

            assertThat(assetService.detail(ASSET_ID, OTHER_ID).status()).isEqualTo(AssetStatus.DRAFT.name());
        }

        @Test
        @DisplayName("普通 USER 角色不享受跨归属权限")
        void plainUserHasNoCrossOwnerAccess() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);
            UserContext.set(OTHER_ID, Role.USER.name());

            assertThatThrownBy(() -> assetService.detail(ASSET_ID, OTHER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);
        }

        @Test
        @DisplayName("资源不存在返回 20001")
        void missingAsset() {
            when(assetMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> assetService.detail(ASSET_ID, OWNER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_NOT_FOUND);
        }
    }

    /* ------------------------------------------------------------ 下载 */

    @Nested
    @DisplayName("下载")
    class Download {

        @Test
        @DisplayName("非 owner 下载草稿被拒绝，且不访问对象存储")
        void shouldRejectDownloadOfDraftByNonOwner() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);

            assertThatThrownBy(() -> assetService.download(ASSET_ID, OTHER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);

            verifyNoInteractions(fileApi);
        }

        @Test
        @DisplayName("没有版本时下载返回文件不存在")
        void shouldFailWhenNoVersion() {
            AssetEntity asset = asset(OWNER_ID, AssetStatus.PUBLISHED);
            asset.setLatestVersion(null);
            when(assetMapper.selectOne(any())).thenReturn(asset);

            assertThatThrownBy(() -> assetService.download(ASSET_ID, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("元数据缺失时返回文件不存在")
        void shouldFailWhenFileMetadataMissing() {
            mockDownloadableAsset();
            when(assetFileMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> assetService.download(ASSET_ID, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("下载成功：文件名取对象路径末段，内容原样返回")
        void shouldReturnBytesWithFileName() {
            mockDownloadableAsset();
            when(fileApi.download("assets/1/1.0.0/package.zip"))
                    .thenReturn(ResponseEntity.ok("ZIP-BYTES".getBytes()));

            AssetDownload download = assetService.download(ASSET_ID, null);

            assertThat(download.fileName()).isEqualTo("package.zip");
            assertThat(new String(download.content())).isEqualTo("ZIP-BYTES");
        }

        @Test
        @DisplayName("下载成功后投递 DownloadCompletedEvent（统计走异步，允许丢失）")
        void downloadShouldEmitDownloadCompletedEvent() throws Exception {
            mockDownloadableAsset();
            when(fileApi.download("assets/1/1.0.0/package.zip"))
                    .thenReturn(ResponseEntity.ok("ZIP-BYTES".getBytes()));
            when(downloadEventDispatcher.getIfAvailable())
                    .thenReturn(org.mockito.Mockito.mock(com.aihub.common.outbox.EventDispatcher.class));

            assetService.download(ASSET_ID, USER_ID);

            org.mockito.ArgumentCaptor<com.aihub.common.outbox.OutboxMessage> captor =
                    org.mockito.ArgumentCaptor.forClass(com.aihub.common.outbox.OutboxMessage.class);
            org.mockito.Mockito.verify(downloadEventDispatcher.getIfAvailable())
                    .dispatch(captor.capture());
            com.aihub.common.outbox.OutboxMessage message = captor.getValue();
            assertThat(message.topic()).isEqualTo(EventTopics.DOWNLOAD_TOPIC);
            assertThat(message.tag()).isEqualTo(EventTopics.DOWNLOAD_COMPLETED);
            // 解析 JSON 后断言：不依赖 Long 的序列化形态（Spring mapper 是字符串、裸 mapper 是数字）
            com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(message.payload());
            assertThat(payload.get("assetId").asLong()).isEqualTo(ASSET_ID);
            assertThat(payload.get("userId").asLong()).isEqualTo(USER_ID);
            assertThat(payload.get("version").asText()).isEqualTo("1.0.0");
            assertThat(message.eventId()).isEqualTo(payload.get("eventId").asText());
        }

        @Test
        @DisplayName("投递失败不影响下载：只记日志，字节照常返回")
        void downloadShouldSucceedEvenIfEventDispatchFails() throws Exception {
            mockDownloadableAsset();
            when(fileApi.download("assets/1/1.0.0/package.zip"))
                    .thenReturn(ResponseEntity.ok("ZIP-BYTES".getBytes()));
            com.aihub.common.outbox.EventDispatcher failing =
                    org.mockito.Mockito.mock(com.aihub.common.outbox.EventDispatcher.class);
            org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
                    .when(failing).dispatch(any(com.aihub.common.outbox.OutboxMessage.class));
            when(downloadEventDispatcher.getIfAvailable()).thenReturn(failing);

            AssetDownload download = assetService.download(ASSET_ID, USER_ID);

            assertThat(new String(download.content())).isEqualTo("ZIP-BYTES");
        }

        @Test
        @DisplayName("下游返回空 body 视为文件不存在，不产出 0 字节文件")
        void shouldFailOnEmptyDownstreamBody() {
            mockDownloadableAsset();
            when(fileApi.download(anyString())).thenReturn(ResponseEntity.ok(null));

            assertThatThrownBy(() -> assetService.download(ASSET_ID, null))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FILE_NOT_FOUND);
        }
    }

    /* ------------------------------------------------------------ 下载 README */

    @Nested
    @DisplayName("下载版本 README")
    class DownloadReadme {

        @Test
        @DisplayName("成功：返回该版本的 README 正文，文件名为 资源名-版本-README.md")
        void shouldReturnReadmeAsMarkdownFile() {
            mockReadableAsset("evil-skill", "1.0.0", "# 标题\n说明文本");

            AssetDownload readme = assetService.downloadReadme(ASSET_ID, "1.0.0", USER_ID);

            assertThat(readme.fileName()).isEqualTo("evil-skill-1.0.0-README.md");
            assertThat(new String(readme.content(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("# 标题\n说明文本");
        }

        @Test
        @DisplayName("每个版本各自一份 README，老版本取到的是老版本的正文")
        void shouldReturnReadmeOfRequestedVersionOnly() {
            mockReadableAsset("my-skill", "1.0.0", "旧版说明");
            AssetDownload old = assetService.downloadReadme(ASSET_ID, "1.0.0", USER_ID);

            mockReadableAsset("my-skill", "2.0.0", "新版说明");
            AssetDownload fresh = assetService.downloadReadme(ASSET_ID, "2.0.0", USER_ID);

            assertThat(new String(old.content(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("旧版说明");
            assertThat(old.fileName()).isEqualTo("my-skill-1.0.0-README.md");
            assertThat(new String(fresh.content(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("新版说明");
        }

        @Test
        @DisplayName("资源名含路径分隔符等非法字符时，文件名被净化（否则落盘会出问题）")
        void shouldSanitizeIllegalCharactersInFileName() {
            mockReadableAsset("a/b:c*d?", "1.0.0", "x");

            AssetDownload readme = assetService.downloadReadme(ASSET_ID, "1.0.0", USER_ID);

            assertThat(readme.fileName()).isEqualTo("a_b_c_d_-1.0.0-README.md");
        }

        @Test
        @DisplayName("版本不存在时报版本不存在")
        void shouldFailWhenVersionMissing() {
            AssetEntity asset = asset(OWNER_ID, AssetStatus.PUBLISHED);
            asset.setName("my-skill");
            when(assetMapper.selectOne(any())).thenReturn(asset);
            when(assetVersionMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> assetService.downloadReadme(ASSET_ID, "9.9.9", USER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_VERSION_NOT_FOUND);
        }

        @Test
        @DisplayName("该版本没有 README 时报文件不存在，不假造一个空文件")
        void shouldFailWhenReadmeBlank() {
            mockReadableAsset("my-skill", "1.0.0", "   ");

            assertThatThrownBy(() -> assetService.downloadReadme(ASSET_ID, "1.0.0", USER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("非 owner 取草稿的 README 被拒绝，且不访问对象存储")
        void shouldRejectReadmeOfDraftByNonOwner() {
            mockAsset(OWNER_ID, AssetStatus.DRAFT);

            assertThatThrownBy(() -> assetService.downloadReadme(ASSET_ID, "1.0.0", OTHER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);

            // README 存在 DB 里，本来就不该碰 File Service
            verifyNoInteractions(fileApi);
        }
    }

    /* ------------------------------------------------------------ 列表 */

    @Test
    @DisplayName("列表：分页参数透传，并把 Page 映射成统一分页契约")
    void listShouldMapPageResult() {
        AssetEntity asset = asset(OWNER_ID, AssetStatus.PUBLISHED);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AssetEntity> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(2, 10);
        page.setTotal(21);
        page.setRecords(List.of(asset));
        when(assetMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);

        PageResult<com.aihub.asset.vo.AssetVo> result =
                assetService.list(new PageQuery(2, 10), null, OWNER_ID, null);

        assertThat(result.total()).isEqualTo(21);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.records()).hasSize(1);
    }

    @Test
    @DisplayName("查自己的同名资源：命中时返回资源与已存在版本列表，未命中返回 null")
    void shouldLookupOwnAssetByName() {
        when(assetMapper.selectList(any())).thenReturn(List.of(asset(OWNER_ID, com.aihub.asset.enums.AssetStatus.PUBLISHED)));
        AssetVersionEntity v1 = new AssetVersionEntity();
        v1.setVersion("1.0.0");
        v1.setDeleted(0L);
        when(assetVersionMapper.selectList(any())).thenReturn(List.of(v1));

        var found = assetService.lookupOwnByName(OWNER_ID, "spring skill");

        assertThat(found).isNotNull();
        assertThat(found.assetId()).isEqualTo(ASSET_ID);
        assertThat(found.versions()).containsExactly("1.0.0");

        when(assetMapper.selectList(any())).thenReturn(List.of());
        assertThat(assetService.lookupOwnByName(OWNER_ID, "nobody")).isNull();
        // 没有身份时不查库、不臆造
        assertThat(assetService.lookupOwnByName(null, "spring skill")).isNull();
    }

    /* ------------------------------------------------------------ 测试辅助 */

    private void mockAsset(long ownerId, AssetStatus status) {
        when(assetMapper.selectOne(any())).thenReturn(asset(ownerId, status));
    }

    /** 与 mockAsset 相同，但额外带上 latestVersion（自动递增要靠它） */
    private void mockAssetWithLatestVersion(long ownerId, AssetStatus status, String latestVersion) {
        AssetEntity asset = asset(ownerId, status);
        asset.setLatestVersion(latestVersion);
        when(assetMapper.selectOne(any())).thenReturn(asset);
    }

    /** 一个已发布、带指定名称与某个版本 README 的资源（README 下载用） */
    private void mockReadableAsset(String assetName, String version, String readme) {
        AssetEntity asset = asset(OWNER_ID, AssetStatus.PUBLISHED);
        asset.setName(assetName);
        asset.setLatestVersion(version);
        when(assetMapper.selectOne(any())).thenReturn(asset);

        AssetVersionEntity versionEntity = new AssetVersionEntity();
        versionEntity.setId(7L);
        versionEntity.setVersion(version);
        versionEntity.setReadme(readme);
        when(assetVersionMapper.selectOne(any())).thenReturn(versionEntity);
    }

    private void mockDownloadableAsset() {
        AssetEntity asset = asset(OWNER_ID, AssetStatus.PUBLISHED);
        asset.setLatestVersion("1.0.0");
        when(assetMapper.selectOne(any())).thenReturn(asset);

        AssetVersionEntity version = new AssetVersionEntity();
        version.setId(7L);
        version.setVersion("1.0.0");
        when(assetVersionMapper.selectOne(any())).thenReturn(version);

        AssetFileEntity file = new AssetFileEntity();
        file.setObjectKey("assets/1/1.0.0/package.zip");
        file.setVersionId(7L);
        when(assetFileMapper.selectOne(any())).thenReturn(file);
    }

    private AssetEntity asset(long ownerId, AssetStatus status) {
        AssetEntity asset = new AssetEntity();
        asset.setId(ASSET_ID);
        asset.setOwnerId(ownerId);
        asset.setStatus(status.name());
        asset.setDeleted(0L);
        return asset;
    }

    /** 一个合法的最小 zip 字节串（含 PK\x03\x04 头） */
    private byte[] zip() {
        byte[] header = {0x50, 0x4B, 0x03, 0x04};
        byte[] body = "payload".getBytes();
        byte[] result = new byte[header.length + body.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(body, 0, result, header.length, body.length);
        return result;
    }

    private Result<FileUploadResponse> uploadOk() {
        return Result.ok(new FileUploadResponse("assets/" + ASSET_ID + "/1.0.0/package.zip",
                21L, "application/zip", "checksum"));
    }

    @Nested
    @DisplayName("下架（Admin）")
    class Suspend {

        @Test
        @DisplayName("非 Admin 下架被拒绝，且不改动任何状态")
        void shouldRejectNonAdmin() {
            UserContext.set(OWNER_ID, Role.USER.name());

            assertThatThrownBy(() -> assetService.suspend(ASSET_ID, OWNER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verify(assetMapper, never()).updateById(any(AssetEntity.class));
            verify(outboxEventService, never()).appendAssetUploaded(any(), anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Admin 下架已发布资源：状态 SUSPENDED、opVersion 递增、事件状态为 SUSPENDED")
        void shouldSuspendPublishedAssetAndEmitEvent() {
            UserContext.set(OWNER_ID, Role.ADMIN.name());
            AssetEntity asset = new AssetEntity();
            asset.setId(ASSET_ID);
            asset.setOwnerId(OWNER_ID);
            asset.setName("Spring Cloud 配置技能");
            asset.setStatus(AssetStatus.PUBLISHED.name());
            asset.setLatestVersion("1.0.0");
            asset.setOpVersion(2L);
            // accessGuard.findActive 用 selectOne
            when(assetMapper.selectOne(any())).thenReturn(asset);
            AssetVersionEntity version = new AssetVersionEntity();
            version.setVersion("1.0.0");
            version.setReadme("README");
            when(assetVersionMapper.selectOne(any())).thenReturn(version);

            assetService.suspend(ASSET_ID, OWNER_ID);

            ArgumentCaptor<AssetEntity> captor = ArgumentCaptor.forClass(AssetEntity.class);
            verify(assetMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AssetStatus.SUSPENDED.name());
            assertThat(captor.getValue().getOpVersion()).isEqualTo(3L);
            // 事件体状态必须是 SUSPENDED：消费端据此删除 ES 文档
            verify(outboxEventService).appendAssetUploaded(asset, "1.0.0", "README", 3L);
            assertThat(asset.getStatus()).isEqualTo(AssetStatus.SUSPENDED.name());
        }

        @Test
        @DisplayName("草稿与已下架的资源不能重复下架")
        void shouldRejectSuspendingNonPublishedAsset() {
            UserContext.set(OWNER_ID, Role.ADMIN.name());
            AssetEntity asset = new AssetEntity();
            asset.setId(ASSET_ID);
            asset.setStatus(AssetStatus.DRAFT.name());
            when(assetMapper.selectOne(any())).thenReturn(asset);

            assertThatThrownBy(() -> assetService.suspend(ASSET_ID, OWNER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_STATUS_INVALID);
        }
    }

    /* ------------------------------------------------------- 列表可见性 */

    @Nested
    @DisplayName("列表：可见性规则与「只看我的」")
    class ListVisibility {

        @Test
        @DisplayName("onlyMine=true：只按 owner 过滤，不再要求 status=PUBLISHED（自己的草稿也要出现）")
        void onlyMineShouldFilterByOwnerOnly() {
            when(assetMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());

            assetService.list(new PageQuery(1, 10), null, OWNER_ID, true);

            ArgumentCaptor<Wrapper<AssetEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(assetMapper).selectPage(any(Page.class), captor.capture());
            String sql = captor.getValue().getSqlSegment();
            assertThat(sql).contains("owner_id");
            // 关键：不能残留可见性条件，否则自己的草稿会被 status=PUBLISHED 挡掉
            assertThat(sql).doesNotContain("status");
            assertThat(paramValues(captor.getValue())).containsValue(OWNER_ID);
        }

        @Test
        @DisplayName("onlyMine=true 但匿名：返回空页且不查库，不能悄悄退化成全站列表")
        void onlyMineAnonymousShouldReturnEmptyWithoutQuery() {
            PageResult<com.aihub.asset.vo.AssetVo> result =
                    assetService.list(new PageQuery(1, 10), null, null, true);

            assertThat(result.records()).isEmpty();
            assertThat(result.total()).isZero();
            verifyNoInteractions(assetMapper);
        }

        @Test
        @DisplayName("未传 onlyMine：仍走原可见性规则（已发布 OR 自己名下，整体包在 AND 组里）")
        void defaultVisibilityKeepsPublishedOrOwn() {
            when(assetMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());

            assetService.list(new PageQuery(1, 10), null, OWNER_ID, null);

            ArgumentCaptor<Wrapper<AssetEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(assetMapper).selectPage(any(Page.class), captor.capture());
            String sql = captor.getValue().getSqlSegment();
            assertThat(sql).contains("status").contains("owner_id").contains("AND (");
        }

        private Map<String, Object> paramValues(Wrapper<?> wrapper) {
            return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        }
    }

    /* ---------------------------------------------------- 编辑与删除 */

    @Nested
    @DisplayName("编辑与删除")
    class UpdateAndDelete {

        @Test
        @DisplayName("编辑：非 owner 被拒绝，且不落库、不发事件")
        void updateShouldRejectNonOwner() {
            AssetEntity asset = new AssetEntity();
            asset.setId(ASSET_ID);
            asset.setOwnerId(OWNER_ID);
            asset.setDeleted(0L);
            when(assetMapper.selectOne(any())).thenReturn(asset);

            assertThatThrownBy(() -> assetService.update(ASSET_ID, OTHER_ID,
                    new UpdateAssetRequest("new", "s", "SKILL")))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);

            verify(assetMapper, never()).updateById(any(AssetEntity.class));
            verify(outboxEventService, never()).appendAssetUploaded(any(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("编辑：名称/简介/分类进 ES 索引，必须 bump opVersion 并发事件（否则搜索停在旧名称）")
        void updateShouldBumpOpVersionAndEmitEvent() {
            AssetEntity asset = new AssetEntity();
            asset.setId(ASSET_ID);
            asset.setOwnerId(OWNER_ID);
            asset.setDeleted(0L);
            asset.setStatus(AssetStatus.PUBLISHED.name());
            asset.setLatestVersion("1.0.0");
            asset.setOpVersion(5L);
            when(assetMapper.selectOne(any())).thenReturn(asset);
            when(assetVersionMapper.selectOne(any())).thenReturn(new AssetVersionEntity());

            assetService.update(ASSET_ID, OWNER_ID, new UpdateAssetRequest("新名称", "新简介", "MCP"));

            ArgumentCaptor<AssetEntity> captor = ArgumentCaptor.forClass(AssetEntity.class);
            verify(assetMapper).updateById(captor.capture());
            AssetEntity saved = captor.getValue();
            assertThat(saved.getName()).isEqualTo("新名称");
            assertThat(saved.getSummary()).isEqualTo("新简介");
            assertThat(saved.getCategory()).isEqualTo("MCP");
            assertThat(saved.getOpVersion()).isEqualTo(6L);

            ArgumentCaptor<AssetEntity> eventAsset = ArgumentCaptor.forClass(AssetEntity.class);
            verify(outboxEventService).appendAssetUploaded(eventAsset.capture(), eq("1.0.0"), any(), eq(6L));
            // 事件体必须是变更后的值，否则索引会写回旧名称
            assertThat(eventAsset.getValue().getName()).isEqualTo("新名称");
        }

        @Test
        @DisplayName("删除：非 owner 被拒绝")
        void deleteShouldRejectNonOwner() {
            AssetEntity asset = new AssetEntity();
            asset.setId(ASSET_ID);
            asset.setOwnerId(OWNER_ID);
            asset.setDeleted(0L);
            when(assetMapper.selectOne(any())).thenReturn(asset);

            assertThatThrownBy(() -> assetService.delete(ASSET_ID, OTHER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);

            verify(assetMapper, never()).updateById(any(AssetEntity.class));
        }

        @Test
        @DisplayName("删除：软删资源且状态置 SUSPENDED，事件携带非 PUBLISHED 状态以便移除索引")
        void deleteShouldSoftDeleteAndEmitNonPublishedStatus() {
            AssetEntity asset = new AssetEntity();
            asset.setId(ASSET_ID);
            asset.setOwnerId(OWNER_ID);
            asset.setDeleted(0L);
            asset.setStatus(AssetStatus.PUBLISHED.name());
            asset.setLatestVersion("1.0.0");
            asset.setOpVersion(1L);
            when(assetMapper.selectOne(any())).thenReturn(asset);
            when(assetVersionMapper.selectOne(any())).thenReturn(new AssetVersionEntity());

            assetService.delete(ASSET_ID, OWNER_ID);

            ArgumentCaptor<AssetEntity> captor = ArgumentCaptor.forClass(AssetEntity.class);
            verify(assetMapper).updateById(captor.capture());
            AssetEntity saved = captor.getValue();
            assertThat(saved.getDeleted()).isNotZero();
            assertThat(saved.getStatus()).isEqualTo(AssetStatus.SUSPENDED.name());

            // 版本与文件元数据一并软删
            verify(assetVersionMapper).update(any(AssetVersionEntity.class), any(Wrapper.class));
            verify(assetFileMapper).update(any(AssetFileEntity.class), any(Wrapper.class));

            ArgumentCaptor<AssetEntity> eventAsset = ArgumentCaptor.forClass(AssetEntity.class);
            verify(outboxEventService).appendAssetUploaded(eventAsset.capture(), any(), any(), eq(2L));
            assertThat(eventAsset.getValue().getStatus()).isEqualTo(AssetStatus.SUSPENDED.name());
        }
    }

    @Nested
    @DisplayName("删除版本（单删/批删）")
    class DeleteVersions {

        private AssetVersionEntity version(long id, String v) {
            AssetVersionEntity entity = new AssetVersionEntity();
            entity.setId(id);
            entity.setAssetId(ASSET_ID);
            entity.setVersion(v);
            entity.setReadme("readme of " + v);
            entity.setDeleted(0L);
            return entity;
        }

        @Test
        @DisplayName("删高版本 → latest 回退到剩余版本中语义最大者，事件携带新 latest 与递增 opVersion")
        void shouldRecomputeLatestBySemanticOrder() {
            AssetEntity asset = asset(OWNER_ID, AssetStatus.PUBLISHED);
            asset.setLatestVersion("2.0.0");
            asset.setOpVersion(1L);
            when(assetMapper.selectOne(any())).thenReturn(asset);
            when(assetVersionMapper.selectList(any())).thenReturn(List.of(
                    version(11L, "1.0.0"), version(12L, "2.0.0")));

            String latest = assetService.deleteVersions(ASSET_ID, List.of("2.0.0"), OWNER_ID);

            assertThat(latest).isEqualTo("1.0.0");
            // latest 重算与 opVersion 经 UpdateWrapper 落库；DB 层的写入由端到端验证覆盖
            verify(assetMapper).update(isNull(), any(Wrapper.class));
            verify(outboxEventService).appendAssetUploaded(any(), eq("1.0.0"), eq("readme of 1.0.0"), eq(2L));
        }

        @Test
        @DisplayName("PUBLISHED 资源删光版本被拒绝——'已发布且无版本'是非法态")
        void shouldRejectDeletingLastVersionOfPublishedAsset() {
            AssetEntity asset = asset(OWNER_ID, AssetStatus.PUBLISHED);
            asset.setLatestVersion("1.0.0");
            asset.setOpVersion(1L);
            when(assetMapper.selectOne(any())).thenReturn(asset);
            when(assetVersionMapper.selectList(any())).thenReturn(List.of(version(11L, "1.0.0")));

            assertThatThrownBy(() -> assetService.deleteVersions(ASSET_ID, List.of("1.0.0"), OWNER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_STATUS_INVALID);
            verify(assetMapper, never()).updateById(any(AssetEntity.class));
            verify(outboxEventService, never()).appendAssetUploaded(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("DRAFT 资源删光版本 → 回到无版本草稿态（latest 置空），事件按 DRAFT 发出")
        void shouldResetLatestToNullWhenDraftHasNoVersionLeft() {
            AssetEntity asset = asset(OWNER_ID, AssetStatus.DRAFT);
            asset.setLatestVersion("1.0.0");
            asset.setOpVersion(3L);
            when(assetMapper.selectOne(any())).thenReturn(asset);
            when(assetVersionMapper.selectList(any())).thenReturn(List.of(version(11L, "1.0.0")));

            String latest = assetService.deleteVersions(ASSET_ID, List.of("1.0.0"), OWNER_ID);

            assertThat(latest).isNull();
            verify(assetMapper).update(isNull(), any(Wrapper.class));
            verify(outboxEventService).appendAssetUploaded(any(), isNull(), isNull(), eq(4L));
        }

        @Test
        @DisplayName("批删幂等：不存在的版本跳过，只删存在的，重算不受影响")
        void shouldSkipUnknownVersionsAndRecompute() {
            AssetEntity asset = asset(OWNER_ID, AssetStatus.DRAFT);
            asset.setLatestVersion("1.0.1");
            asset.setOpVersion(1L);
            when(assetMapper.selectOne(any())).thenReturn(asset);
            when(assetVersionMapper.selectList(any())).thenReturn(List.of(
                    version(11L, "1.0.0"), version(12L, "1.0.1")));

            String latest = assetService.deleteVersions(ASSET_ID, List.of("1.0.1", "9.9.9"), OWNER_ID);

            // 剩 1.0.0；语义比较下 1.0.0 < 1.0.1，删掉 1.0.1 后 latest 回退
            assertThat(latest).isEqualTo("1.0.0");
            verify(assetVersionMapper).update(any(AssetVersionEntity.class), any(Wrapper.class));
            verify(assetFileMapper).update(any(AssetFileEntity.class), any(Wrapper.class));
        }

        @Test
        @DisplayName("非 owner 删版本被拒绝")
        void shouldRejectNonOwner() {
            when(assetMapper.selectOne(any())).thenReturn(asset(OWNER_ID, AssetStatus.DRAFT));

            assertThatThrownBy(() -> assetService.deleteVersions(ASSET_ID, List.of("1.0.0"), OTHER_ID))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);
            verify(assetVersionMapper, never()).update(any(AssetVersionEntity.class), any(Wrapper.class));
        }
    }

    @Test
    @DisplayName("语义版本比较：逐段数值比较，1.10.0 > 1.9.0，非数字段退化字符串比较")
    void semanticVersionComparison() {
        assertThat(AssetServiceImpl.compareSemanticVersion("1.10.0", "1.9.0")).isPositive();
        assertThat(AssetServiceImpl.compareSemanticVersion("1.0.0", "1.0.0")).isZero();
        assertThat(AssetServiceImpl.compareSemanticVersion("2.0", "1.9.9")).isPositive();
        // 含非数字段时回退为字符串比较（"1..." < "a..."），实际版本号由上传校验保证不会走到这
        assertThat(AssetServiceImpl.compareSemanticVersion("1.0.0", "abc")).isNegative();
    }
}
