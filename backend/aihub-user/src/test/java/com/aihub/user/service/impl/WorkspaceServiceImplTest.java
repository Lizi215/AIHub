package com.aihub.user.service.impl;

import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.dto.AssetQueryDetail;
import com.aihub.api.dto.AssetQueryVersion;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.Result;
import com.aihub.user.dto.CreateWorkspaceRequest;
import com.aihub.user.entity.WorkspaceEntity;
import com.aihub.user.entity.WorkspaceItemEntity;
import com.aihub.user.mapper.WorkspaceItemMapper;
import com.aihub.user.mapper.WorkspaceMapper;
import com.aihub.user.vo.WorkspaceVo;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workspace：条目校验、去重、归属隔离。
 *
 * <p>重点：items 不可信任（逐项经 Feign 校验）、同一资源去重、
 * 归属隔离（他人 workspace 一律 NOT_FOUND，不泄露存在性）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long ASSET_A = 100L;
    private static final long ASSET_B = 200L;
    private static final long WS_ID = 900L;

    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private WorkspaceItemMapper workspaceItemMapper;
    @Mock
    private AssetQueryApi assetQueryApi;

    private WorkspaceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceServiceImpl(workspaceMapper, workspaceItemMapper, assetQueryApi);
    }

    @Test
    @DisplayName("创建：逐项校验通过后落库")
    void shouldCreateWithValidation() {
        when(assetQueryApi.detail(ASSET_A)).thenReturn(Result.ok(publishedDetail(ASSET_A, "1.0.0")));
        when(assetQueryApi.detail(ASSET_B)).thenReturn(Result.ok(publishedDetail(ASSET_B, "2.0.0")));

        CreateWorkspaceRequest request = new CreateWorkspaceRequest("我的组合", null, List.of(
                new CreateWorkspaceRequest.Item(ASSET_A, null),
                new CreateWorkspaceRequest.Item(ASSET_B, "2.0.0")));

        service.create(USER_ID, request);

        ArgumentCaptor<WorkspaceEntity> wsCaptor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceMapper).insert(wsCaptor.capture());
        assertThat(wsCaptor.getValue().getName()).isEqualTo("我的组合");
        verify(workspaceItemMapper, org.mockito.Mockito.times(2)).insert(any(WorkspaceItemEntity.class));
    }

    @Test
    @DisplayName("同一资源在 items 中重复出现被拒绝（输入错误而不是静默合并）")
    void shouldRejectDuplicateAssetId() {
        when(assetQueryApi.detail(ASSET_A)).thenReturn(Result.ok(publishedDetail(ASSET_A, "1.0.0")));

        CreateWorkspaceRequest request = new CreateWorkspaceRequest("组合", null, List.of(
                new CreateWorkspaceRequest.Item(ASSET_A, null),
                new CreateWorkspaceRequest.Item(ASSET_A, null)));

        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("duplicate assetId");
    }

    @Test
    @DisplayName("未发布/不存在的资产被拒绝，并指明具体条目")
    void shouldRejectUnavailableAsset() {
        when(assetQueryApi.detail(ASSET_A)).thenReturn(
                Result.fail(ErrorCode.NOT_FOUND, "asset not found"));

        CreateWorkspaceRequest request = new CreateWorkspaceRequest("组合", null, List.of(
                new CreateWorkspaceRequest.Item(ASSET_A, null)));

        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("100")   // 原因里带 asset id
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("指定版本不存在于该资产时被拒绝")
    void shouldRejectUnknownVersion() {
        when(assetQueryApi.detail(ASSET_A)).thenReturn(Result.ok(publishedDetail(ASSET_A, "1.0.0")));

        CreateWorkspaceRequest request = new CreateWorkspaceRequest("组合", null, List.of(
                new CreateWorkspaceRequest.Item(ASSET_A, "9.9.9")));

        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("9.9.9");
    }

    @Test
    @DisplayName("超过 20 条被拒绝")
    void shouldRejectTooManyItems() {
        List<CreateWorkspaceRequest.Item> items = new java.util.ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            items.add(new CreateWorkspaceRequest.Item(1000L + i, null));
        }

        assertThatThrownBy(() -> service.create(USER_ID, new CreateWorkspaceRequest("组合", null, items)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("详情：他人 workspace 一律 NOT_FOUND（不泄露存在性）")
    void detailShouldIsolateByOwner() {
        when(workspaceMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.detail(USER_ID, WS_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("详情：条目回查名字与可用性，下架资源如实标记")
    void detailShouldResolveAvailability() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(WS_ID);
        workspace.setUserId(USER_ID);
        workspace.setName("我的组合");
        when(workspaceMapper.selectOne(any(Wrapper.class))).thenReturn(workspace);
        when(workspaceItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                item(1L, ASSET_A, "1.0.0"),
                item(2L, ASSET_B, "2.0.0")));
        // A 仍可用；B 已查不到（被删除）→ available=false 且 name=null
        when(assetQueryApi.detail(ASSET_A)).thenReturn(Result.ok(publishedDetail(ASSET_A, "1.0.0")));
        when(assetQueryApi.detail(ASSET_B)).thenReturn(Result.fail(ErrorCode.NOT_FOUND, "not found"));

        WorkspaceVo vo = service.detail(USER_ID, WS_ID);

        assertThat(vo.items()).hasSize(2);
        assertThat(vo.items().get(0).name()).isEqualTo("Asset 100");
        assertThat(vo.items().get(0).available()).isTrue();
        assertThat(vo.items().get(1).available()).isFalse();
        assertThat(vo.items().get(1).name()).isNull();
    }

    @Test
    @DisplayName("删除：workspace 与条目一起软删")
    void deleteShouldSoftDeleteWorkspaceAndItems() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(WS_ID);
        workspace.setUserId(USER_ID);
        when(workspaceMapper.selectOne(any(Wrapper.class))).thenReturn(workspace);

        service.delete(USER_ID, WS_ID);

        verify(workspaceMapper).deleteById(WS_ID);
        verify(workspaceItemMapper).update(any(WorkspaceItemEntity.class), any(Wrapper.class));
    }

    @Test
    @DisplayName("列表：只返回自己的 workspace")
    void listShouldFilterByOwner() {
        Page<WorkspaceEntity> page = new Page<>(1, 10);
        page.setRecords(List.of());
        when(workspaceMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        service.list(USER_ID, new PageQuery(1, 10));

        org.mockito.Mockito.verify(workspaceMapper).selectPage(any(Page.class), any(Wrapper.class));
    }

    /* ------------------------------------------------------------------ 构造 */

    private AssetQueryDetail publishedDetail(long assetId, String version) {
        AssetQueryVersion v = new AssetQueryVersion(
                assetId * 10, version, null, null, "PUBLISHED", null, 1024L,
                true, "/api/v1/assets/" + assetId + "/versions/" + version + "/download");
        return new AssetQueryDetail(assetId, 1L, "Asset " + assetId, null, "SKILL",
                "PUBLISHED", version, null, List.of(v));
    }

    private WorkspaceItemEntity item(Long id, long assetId, String version) {
        WorkspaceItemEntity entity = new WorkspaceItemEntity();
        entity.setId(id);
        entity.setWorkspaceId(WS_ID);
        entity.setAssetId(assetId);
        entity.setVersion(version);
        return entity;
    }
}
