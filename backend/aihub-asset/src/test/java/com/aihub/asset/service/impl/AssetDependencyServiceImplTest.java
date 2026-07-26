package com.aihub.asset.service.impl;

import com.aihub.asset.dependency.PackageDependencyParser;
import com.aihub.asset.entity.AssetDependencyEntity;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.mapper.AssetDependencyMapper;
import com.aihub.asset.mapper.AssetMapper;
import com.aihub.asset.service.AssetAccessGuard;
import com.aihub.asset.vo.AssetDependencyVo;
import com.aihub.asset.vo.DependencyNodeVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 依赖服务：上传声明同步（syncFromManifest）+ 展示组装。
 *
 * <p>依赖统一由上传包内的 aihub.yaml 声明产生，因此"重复 / 成环 / 目标不存在"三条不变式
 * （docs/ARCHITECTURE.md §8.4）的守卫测试都在同步入口上。
 *
 * <p>用真实的 {@link AssetAccessGuard}（只 mock 它背后的 mapper），归属校验走生产代码。
 */
@ExtendWith(MockitoExtension.class)
class AssetDependencyServiceImplTest {

    private static final long ASSET_ID = 100L;
    private static final long OWNER_ID = 1L;
    private static final long TARGET_ID = 200L;

    @Mock
    private AssetDependencyMapper dependencyMapper;
    @Mock
    private AssetMapper assetMapper;

    private AssetDependencyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AssetDependencyServiceImpl(dependencyMapper, assetMapper,
                new AssetAccessGuard(assetMapper), 32);
    }

    /* --------------------------------------------------- 上传声明同步 */

    @Test
    @DisplayName("声明里的资源命中已发布资源 → 写入依赖并回报已解析条数")
    void shouldSyncResolvedRequirement() {
        stubSelf("self-skill");
        stubPublishedByName("base-skill", TARGET_ID);
        when(dependencyMapper.selectList(any())).thenReturn(List.of());

        List<String> notes = service.syncFromManifest(ASSET_ID,
                List.of(new PackageDependencyParser.Requirement("base-skill", ">=1.0.0")), true);

        ArgumentCaptor<AssetDependencyEntity> captor = ArgumentCaptor.forClass(AssetDependencyEntity.class);
        verify(dependencyMapper).insert(captor.capture());
        assertThat(captor.getValue().getAssetId()).isEqualTo(ASSET_ID);
        assertThat(captor.getValue().getDependsOnAssetId()).isEqualTo(TARGET_ID);
        assertThat(captor.getValue().getVersionConstraint()).isEqualTo(">=1.0.0");
        assertThat(captor.getValue().getDeleted()).isZero();
        assertThat(notes).contains("已解析 1 条依赖");
    }

    @Test
    @DisplayName("依赖目标不存在 → 不写库，如实回报已跳过")
    void shouldSkipMissingTarget() {
        stubSelf("self-skill");
        when(assetMapper.selectList(any())).thenReturn(List.of());

        List<String> notes = service.syncFromManifest(ASSET_ID,
                List.of(new PackageDependencyParser.Requirement("ghost", null)), true);

        verify(dependencyMapper, never()).insert(any(AssetDependencyEntity.class));
        assertThat(notes).anySatisfy(n -> assertThat(n).contains("未找到可依赖的资源「ghost」"));
    }

    @Test
    @DisplayName("依赖目标是归属者自己的草稿 → 可解析（同批上传的关键场景）")
    void shouldResolveOwnDraftAsDependencyTarget() {
        stubSelf("self-skill");
        AssetEntity draft = asset(210L, OWNER_ID);
        draft.setStatus("DRAFT");
        // selectList 调用顺序：① published 查询（空）② 自己的草稿查询（命中）
        when(assetMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(draft));

        List<String> notes = service.syncFromManifest(ASSET_ID,
                List.of(new PackageDependencyParser.Requirement("same-batch-skill", null)), true);

        ArgumentCaptor<AssetDependencyEntity> captor = ArgumentCaptor.forClass(AssetDependencyEntity.class);
        verify(dependencyMapper).insert(captor.capture());
        assertThat(captor.getValue().getDependsOnAssetId()).isEqualTo(210L);
        assertThat(notes).contains("已解析 1 条依赖");
    }

    @Test
    @DisplayName("依赖目标是别人的草稿 → 不可解析（草稿是私有的）")
    void shouldNotResolveOthersDraftAsDependencyTarget() {
        stubSelf("self-skill");
        when(assetMapper.selectList(any())).thenReturn(List.of());

        List<String> notes = service.syncFromManifest(ASSET_ID,
                List.of(new PackageDependencyParser.Requirement("others-draft", null)), true);

        verify(dependencyMapper, never()).insert(any(AssetDependencyEntity.class));
        assertThat(notes).anySatisfy(n -> assertThat(n).contains("未找到可依赖的资源"));
    }

    @Test
    @DisplayName("自依赖被跳过：依赖名与本资源名相同")
    void shouldSkipSelfDependency() {
        stubSelf("self-skill");

        List<String> notes = service.syncFromManifest(ASSET_ID,
                List.of(new PackageDependencyParser.Requirement("SELF-SKILL", null)), true);

        verify(dependencyMapper, never()).insert(any(AssetDependencyEntity.class));
        assertThat(notes).anySatisfy(n -> assertThat(n).contains("跳过自依赖"));
    }

    @Test
    @DisplayName("成环被拒绝：目标资源已（间接）依赖本资源 → 跳过并说明（docs/ARCHITECTURE.md §8.4）")
    void shouldSkipCyclicDependency() {
        stubSelf("self-skill");
        stubPublishedByName("loop-skill", TARGET_ID);
        // 调用顺序：① 同步前的现有依赖 ② 重复检查（本资源无依赖）
        //           ③ reaches 探到目标 TARGET 依赖本资源 → 成环
        when(dependencyMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(dependency(TARGET_ID, ASSET_ID)))
                .thenReturn(List.of());

        List<String> notes = service.syncFromManifest(ASSET_ID,
                List.of(new PackageDependencyParser.Requirement("loop-skill", null)), true);

        verify(dependencyMapper, never()).insert(any(AssetDependencyEntity.class));
        assertThat(notes).anySatisfy(n -> assertThat(n).contains("会形成循环依赖"));
    }

    @Test
    @DisplayName("已存在的依赖不重复插入，且保留（不算错误）")
    void shouldKeepExistingEdge() {
        stubSelf("self-skill");
        stubPublishedByName("base-skill", TARGET_ID);
        AssetDependencyEntity existing = dependency(ASSET_ID, TARGET_ID);
        existing.setId(9L);
        when(dependencyMapper.selectList(any())).thenReturn(List.of(existing));

        List<String> notes = service.syncFromManifest(ASSET_ID,
                List.of(new PackageDependencyParser.Requirement("base-skill", null)), true);

        verify(dependencyMapper, never()).insert(any(AssetDependencyEntity.class));
        verify(dependencyMapper, never()).updateById(any(AssetDependencyEntity.class));
        assertThat(notes).contains("已解析 1 条依赖");
    }

    @Test
    @DisplayName("声明里没有的旧依赖被软删除：deleted 写时间戳而非 0/1")
    void shouldSoftDeleteStaleDependency() {
        stubSelf("self-skill");
        when(assetMapper.selectList(any())).thenReturn(List.of());  // 新声明解析不到任何资源
        AssetDependencyEntity stale = dependency(ASSET_ID, TARGET_ID);
        stale.setId(7L);
        when(dependencyMapper.selectList(any())).thenReturn(List.of(stale));

        List<String> notes = service.syncFromManifest(ASSET_ID,
                List.of(new PackageDependencyParser.Requirement("ghost", null)), true);

        ArgumentCaptor<AssetDependencyEntity> captor = ArgumentCaptor.forClass(AssetDependencyEntity.class);
        verify(dependencyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
        assertThat(captor.getValue().getDeleted()).isNotNull().isGreaterThan(0L);
        assertThat(notes).anySatisfy(n -> assertThat(n).contains("已移除 1 条"));
    }

    @Test
    @DisplayName("并发下唯一索引冲突被吞掉：视为已同步，不让上传失败")
    void shouldTolerateDuplicateKeyOnConcurrentSync() {
        stubSelf("self-skill");
        stubPublishedByName("base-skill", TARGET_ID);
        when(dependencyMapper.selectList(any())).thenReturn(List.of());
        when(dependencyMapper.insert(any(AssetDependencyEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_asset_dependency"));

        List<String> notes = service.syncFromManifest(ASSET_ID,
                List.of(new PackageDependencyParser.Requirement("base-skill", null)), true);

        assertThat(notes).contains("已解析 1 条依赖");
        verify(dependencyMapper, times(1)).insert(any(AssetDependencyEntity.class));
    }

    @Test
    @DisplayName("新版本包内没有 aihub.yaml：视为空声明，旧依赖被清空且提示说明原因")
    void shouldClearDependenciesWhenManifestAbsent() {
        stubSelf("self-skill");
        AssetDependencyEntity stale = dependency(ASSET_ID, TARGET_ID);
        stale.setId(11L);
        when(dependencyMapper.selectList(any())).thenReturn(List.of(stale));

        List<String> notes = service.syncFromManifest(ASSET_ID, List.of(), false);

        ArgumentCaptor<AssetDependencyEntity> captor = ArgumentCaptor.forClass(AssetDependencyEntity.class);
        verify(dependencyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDeleted()).isGreaterThan(0L);
        assertThat(notes).anySatisfy(n -> assertThat(n)
                .contains("本次包内没有 aihub.yaml").contains("已移除此前的 1 条依赖"));
    }

    /* ------------------------------------------------------------ 展示 */

    @Test
    @DisplayName("直接依赖列表带出被依赖资源名与最新版本")
    void shouldListDirectWithTargetInfo() {
        AssetDependencyEntity row = dependency(ASSET_ID, TARGET_ID);
        row.setId(9L);
        row.setVersionConstraint(">=1.0.0");
        when(assetMapper.selectOne(any())).thenReturn(asset(ASSET_ID, OWNER_ID));
        when(dependencyMapper.selectList(any())).thenReturn(List.of(row));

        AssetEntity target = asset(TARGET_ID, OWNER_ID);
        target.setName("base-skill");
        target.setLatestVersion("1.2.0");
        when(assetMapper.selectByIds(any())).thenReturn(List.of(target));

        List<AssetDependencyVo> result = service.listDirect(ASSET_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).assetId()).isEqualTo(TARGET_ID);
        assertThat(result.get(0).name()).isEqualTo("base-skill");
        assertThat(result.get(0).latestVersion()).isEqualTo("1.2.0");
        assertThat(result.get(0).versionConstraint()).isEqualTo(">=1.0.0");
    }

    @Test
    @DisplayName("依赖树按层级组装并带出资源名")
    void shouldBuildTreeWithNames() {
        when(assetMapper.selectOne(any())).thenReturn(asset(ASSET_ID, OWNER_ID));
        // ASSET_ID → TARGET(200) → 300
        when(dependencyMapper.selectList(any()))
                .thenReturn(List.of(dependency(ASSET_ID, TARGET_ID)))
                .thenReturn(List.of(dependency(TARGET_ID, 300L)))
                .thenReturn(List.of());

        AssetEntity target = asset(TARGET_ID, OWNER_ID);
        target.setName("level-1");
        AssetEntity leaf = asset(300L, OWNER_ID);
        leaf.setName("level-2");
        when(assetMapper.selectByIds(any())).thenReturn(List.of(target, leaf));

        List<DependencyNodeVo> tree = service.dependencyTree(ASSET_ID, OWNER_ID);

        assertThat(tree).hasSize(1);
        DependencyNodeVo first = tree.get(0);
        assertThat(first.assetId()).isEqualTo(TARGET_ID);
        assertThat(first.name()).isEqualTo("level-1");
        assertThat(first.cycle()).isFalse();
        assertThat(first.children()).hasSize(1);
        assertThat(first.children().get(0).assetId()).isEqualTo(300L);
        assertThat(first.children().get(0).name()).isEqualTo("level-2");
    }

    @Test
    @DisplayName("没有依赖时返回空树，不额外查库")
    void shouldReturnEmptyTreeWhenNoDependency() {
        when(assetMapper.selectOne(any())).thenReturn(asset(ASSET_ID, OWNER_ID));
        when(dependencyMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.dependencyTree(ASSET_ID, OWNER_ID)).isEmpty();
    }

    /* -------------------------------------------------------- 测试辅助 */

    /** 本资源自身：名字用于自依赖判断 */
    private void stubSelf(String name) {
        AssetEntity self = asset(ASSET_ID, OWNER_ID);
        self.setName(name);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(self);
    }

    /** stubPublishedByName 的桩：命中则返回该资源，否则空列表 */
    private void stubPublishedByName(String name, Long id) {
        AssetEntity found = asset(id, OWNER_ID);
        found.setName(name);
        when(assetMapper.selectList(any())).thenReturn(List.of(found));
    }

    private AssetEntity asset(Long id, Long ownerId) {
        AssetEntity entity = new AssetEntity();
        entity.setId(id);
        entity.setOwnerId(ownerId);
        entity.setStatus("PUBLISHED");
        entity.setDeleted(0L);
        return entity;
    }

    private AssetDependencyEntity dependency(Long assetId, Long dependsOnId) {
        AssetDependencyEntity entity = new AssetDependencyEntity();
        entity.setAssetId(assetId);
        entity.setDependsOnAssetId(dependsOnId);
        entity.setDeleted(0L);
        return entity;
    }
}
