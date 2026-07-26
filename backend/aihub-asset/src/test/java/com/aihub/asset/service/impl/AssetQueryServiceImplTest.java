package com.aihub.asset.service.impl;

import com.aihub.api.dto.AssetQueryDetail;
import com.aihub.api.dto.AssetQueryVersion;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.entity.AssetFileEntity;
import com.aihub.asset.entity.AssetVersionEntity;
import com.aihub.asset.enums.AssetStatus;
import com.aihub.asset.enums.VersionPublishStatus;
import com.aihub.asset.mapper.AssetFileMapper;
import com.aihub.asset.mapper.AssetMapper;
import com.aihub.asset.mapper.AssetVersionMapper;
import com.aihub.asset.service.AssetAccessGuard;
import com.aihub.asset.service.AssetDependencyService;
import com.aihub.asset.vo.DependencyNodeVo;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Agent 内部只读查询：可见性、关键字过滤的 SQL 形态、下载可用性判定。
 *
 * <p>可见性用真实的 {@link AssetAccessGuard}（只 mock 它背后的 mapper）：「Agent 看不到草稿」
 * 是最要紧的一条约束，不能在测试里被 mock 绕过。
 *
 * <p>{@code LambdaQueryWrapper} 解析列名依赖 TableInfo 缓存，纯 Mockito 单测没有 MyBatis 启动过程，
 * 所以这里显式初始化一次。
 */
@ExtendWith(MockitoExtension.class)
class AssetQueryServiceImplTest {

    private static final long ASSET_ID = 100L;
    private static final long OWNER_ID = 1L;

    @Mock
    private AssetMapper assetMapper;
    @Mock
    private AssetVersionMapper assetVersionMapper;
    @Mock
    private AssetFileMapper assetFileMapper;
    @Mock
    private AssetDependencyService dependencyService;
    @Mock
    private com.aihub.asset.mapper.AssetTagMapper assetTagMapper;
    @Mock
    private com.aihub.asset.mapper.TagMapper tagMapper;

    private AssetQueryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, AssetEntity.class);
        TableInfoHelper.initTableInfo(assistant, AssetVersionEntity.class);
        TableInfoHelper.initTableInfo(assistant, AssetFileEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new AssetQueryServiceImpl(assetMapper, assetTagMapper, tagMapper, assetVersionMapper,
                assetFileMapper, dependencyService, new AssetAccessGuard(assetMapper));
    }

    @Test
    @DisplayName("检索：只查已发布资源，且关键字过滤被包在 AND 组里，不会被 OR 绕开可见性")
    void searchShouldFilterPublishedAndGroupKeyword() {
        when(assetMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());

        service.search("  spring  ", "SKILL", null, new PageQuery(1, 5));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AssetEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(assetMapper).selectPage(any(Page.class), captor.capture());

        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("status").contains("category").contains("LIKE").contains("ORDER BY");
        // 关键字条件必须以 AND ( ... OR ... ) 的形式出现：直接拼 OR 会把 status = PUBLISHED 一起放行
        assertThat(sql).contains("AND (");

        Map<String, Object> params = paramValues(captor.getValue());
        assertThat(params.values()).contains(AssetStatus.PUBLISHED.name(), "SKILL", "%spring%");
    }

    @Test
    @DisplayName("检索：关键字与分类为空时不追加对应条件")
    void searchShouldSkipBlankFilters() {
        when(assetMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());

        service.search(null, "  ", null, new PageQuery(null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AssetEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(assetMapper).selectPage(any(Page.class), captor.capture());

        assertThat(captor.getValue().getSqlSegment()).doesNotContain("LIKE").doesNotContain("category");
    }

    @Test
    @DisplayName("检索：分页参数未传时按默认值归一化（不传 null 给数据库）")
    void searchShouldNormalizePage() {
        when(assetMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());

        service.search(null, null, null, new PageQuery(null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<AssetEntity>> captor = ArgumentCaptor.forClass(Page.class);
        org.mockito.Mockito.verify(assetMapper).selectPage(captor.capture(), any(Wrapper.class));
        assertThat(captor.getValue().getCurrent()).isEqualTo(PageQuery.DEFAULT_PAGE);
        assertThat(captor.getValue().getSize()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("详情：草稿对 Agent 不可见——匿名视角下抛 20002，而不是把草稿内容喂进模型上下文")
    void detailShouldRejectDraft() {
        when(assetMapper.selectOne(any(Wrapper.class))).thenReturn(asset(AssetStatus.DRAFT));

        assertThatThrownBy(() -> service.detail(ASSET_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET_ACCESS_DENIED);
    }

    @Test
    @DisplayName("详情：版本可下载时才给出下载地址，未发布的版本明确标记不可下载")
    void detailShouldMarkDownloadable() {
        when(assetMapper.selectOne(any(Wrapper.class))).thenReturn(asset(AssetStatus.PUBLISHED));
        when(assetVersionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(version(1L, "2.0.0", VersionPublishStatus.DRAFT),
                        version(2L, "1.0.0", VersionPublishStatus.PUBLISHED)));
        when(assetFileMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(file(1L), file(2L)));

        AssetQueryDetail detail = service.detail(ASSET_ID);

        assertThat(detail.versions()).hasSize(2);
        AssetQueryVersion draft = detail.versions().get(0);
        assertThat(draft.version()).isEqualTo("2.0.0");
        assertThat(draft.downloadable()).isFalse();
        assertThat(draft.downloadUrl()).isNull();

        AssetQueryVersion published = detail.versions().get(1);
        assertThat(published.downloadable()).isTrue();
        assertThat(published.downloadUrl()).isEqualTo("/api/v1/assets/100/versions/1.0.0/download");
        assertThat(published.size()).isEqualTo(2048L);
    }

    @Test
    @DisplayName("详情：文件元数据缺失的版本不可下载——否则用户确认完才失败，等同于伪造执行结果")
    void detailShouldMarkNotDownloadableWhenFileMissing() {
        when(assetMapper.selectOne(any(Wrapper.class))).thenReturn(asset(AssetStatus.PUBLISHED));
        when(assetVersionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(version(1L, "1.0.0", VersionPublishStatus.PUBLISHED)));
        when(assetFileMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AssetQueryDetail detail = service.detail(ASSET_ID);

        assertThat(detail.versions().get(0).downloadable()).isFalse();
        assertThat(detail.versions().get(0).downloadUrl()).isNull();
        // README 与文件元数据是两回事：文件没落地不代表版本文本不存在，README 仍应回传
        assertThat(detail.readme()).isEqualTo("readme of 1.0.0");
    }

    @Test
    @DisplayName("详情：版本过多时截断，避免上下文被版本历史撑爆")
    void detailShouldTruncateVersions() {
        when(assetMapper.selectOne(any(Wrapper.class))).thenReturn(asset(AssetStatus.PUBLISHED));
        List<AssetVersionEntity> many = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(version((long) i, "1.0." + i, VersionPublishStatus.PUBLISHED));
        }
        when(assetVersionMapper.selectList(any(Wrapper.class))).thenReturn(many);
        when(assetFileMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.detail(ASSET_ID).versions()).hasSize(20);
    }

    @Test
    @DisplayName("依赖树：递归映射 assetId / name / cycle")
    void dependencyTreeShouldMapRecursively() {
        when(dependencyService.dependencyTree(ASSET_ID, null)).thenReturn(List.of(
                new DependencyNodeVo(200L, "dep-a", false, List.of(
                        new DependencyNodeVo(300L, "dep-b", false, List.of()),
                        new DependencyNodeVo(200L, "dep-a", true, List.of())))));

        var tree = service.dependencyTree(ASSET_ID);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).assetId()).isEqualTo(200L);
        assertThat(tree.get(0).name()).isEqualTo("dep-a");
        assertThat(tree.get(0).children()).hasSize(2);
        assertThat(tree.get(0).children().get(1).cycle()).isTrue();
    }

    @Test
    @DisplayName("检索结果映射：列表项字段与对外列表保持同一语义")
    void searchShouldMapItems() {
        AssetEntity entity = asset(AssetStatus.PUBLISHED);
        entity.setLatestVersion("1.0.0");
        Page<AssetEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(entity));
        page.setTotal(1);
        when(assetMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        PageResult<com.aihub.api.dto.AssetQueryItem> result = service.search(null, null, null, new PageQuery(1, 10));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records().get(0).id()).isEqualTo(ASSET_ID);
        assertThat(result.records().get(0).latestVersion()).isEqualTo("1.0.0");
    }

    /* ------------------------------------------------------------------ 构造 */

    /** 取 wrapper 上绑定的参数值。{@code getParamNameValuePairs} 在 AbstractWrapper 上，不在 Wrapper 接口上。 */
    private static Map<String, Object> paramValues(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    private AssetEntity asset(AssetStatus status) {
        AssetEntity asset = new AssetEntity();
        asset.setId(ASSET_ID);
        asset.setOwnerId(OWNER_ID);
        asset.setName("Spring Cloud Skill");
        asset.setSummary("spring cloud 相关技能");
        asset.setCategory("SKILL");
        asset.setStatus(status.name());
        return asset;
    }

    private AssetVersionEntity version(Long id, String version, VersionPublishStatus status) {
        AssetVersionEntity entity = new AssetVersionEntity();
        entity.setId(id);
        entity.setAssetId(ASSET_ID);
        entity.setVersion(version);
        entity.setPublishStatus(status.name());
        entity.setReadme("readme of " + version);
        return entity;
    }

    private AssetFileEntity file(Long versionId) {
        AssetFileEntity file = new AssetFileEntity();
        file.setAssetId(ASSET_ID);
        file.setVersionId(versionId);
        file.setObjectKey("assets/100/1.0.0/package.zip");
        file.setSize(2048L);
        file.setChecksum("abc");
        file.setContentType("application/zip");
        return file;
    }
}
