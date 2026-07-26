package com.aihub.asset.service.impl;

import com.aihub.asset.entity.AssetTagEntity;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.entity.TagEntity;
import com.aihub.asset.mapper.AssetTagMapper;
import com.aihub.asset.mapper.TagMapper;
import com.aihub.asset.service.AssetAccessGuard;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeAll;
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
 * 标签服务：创建幂等、整组替换、owner 校验、名字清洗。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TagServiceImplTest {

    private static final long ASSET_ID = 100L;
    private static final long OWNER_ID = 7L;

    @Mock
    private TagMapper tagMapper;
    @Mock
    private AssetTagMapper assetTagMapper;
    @Mock
    private AssetAccessGuard accessGuard;
    @Mock
    private com.aihub.asset.mapper.AssetMapper assetMapper;
    @Mock
    private com.aihub.asset.mapper.AssetVersionMapper assetVersionMapper;
    @Mock
    private com.aihub.asset.service.OutboxEventService outboxEventService;

    private TagServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 解析列名依赖 TableInfo 缓存，纯 Mockito 单测需显式初始化
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TagEntity.class);
        TableInfoHelper.initTableInfo(assistant, AssetTagEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new TagServiceImpl(tagMapper, assetTagMapper, accessGuard,
                assetMapper, assetVersionMapper, outboxEventService);
        com.aihub.asset.entity.AssetEntity asset = new com.aihub.asset.entity.AssetEntity();
        asset.setId(ASSET_ID);
        asset.setOwnerId(OWNER_ID);
        asset.setOpVersion(1L);
        when(accessGuard.requireOwned(ASSET_ID, OWNER_ID)).thenReturn(asset);
    }

    @Test
    @DisplayName("创建：同名幂等返回既有 id")
    void createShouldBeIdempotent() {
        TagEntity existing = new TagEntity();
        existing.setId(9L);
        existing.setName("spring");
        when(tagMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        Long id = service.create(" spring ");

        assertThat(id).isEqualTo(9L);
    }

    @Test
    @DisplayName("超长标签名被拒绝（会进 ES keyword 字段，超长被 ES 拒绝）")
    void shouldRejectTooLongName() {
        assertThatThrownBy(() -> service.create("x".repeat(21)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("整组替换：软删旧关联后按清洗后的名单重建")
    void setAssetTagsShouldReplaceSet() {
        service.setAssetTags(ASSET_ID, List.of(" spring ", "", "cloud", "spring"), OWNER_ID);

        // 清洗后只剩 spring/cloud 两个（去重、去空）
        ArgumentCaptor<TagEntity> tagCaptor = ArgumentCaptor.forClass(TagEntity.class);
        verify(tagMapper, org.mockito.Mockito.times(2)).insert(tagCaptor.capture());
        // 软删旧关联 + 新关联插入
        verify(assetTagMapper).update(any(AssetTagEntity.class), any(Wrapper.class));
        verify(assetTagMapper, org.mockito.Mockito.times(2)).insert(any(AssetTagEntity.class));
        assertThat(tagCaptor.getAllValues().stream().map(TagEntity::getName))
                .containsExactlyInAnyOrder("spring", "cloud");
        // 标签变化必须触发索引同步：opVersion 递增 + outbox 事件（否则 ES 停在旧标签）
        verify(assetMapper).updateById(any(com.aihub.asset.entity.AssetEntity.class));
        verify(outboxEventService).appendAssetUploaded(any(), any(), any(), org.mockito.Mockito.eq(2L));
    }

    @Test
    @DisplayName("超过 10 个标签被拒绝")
    void shouldRejectTooManyTags() {
        List<String> tooMany = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "tag" + i).toList();

        assertThatThrownBy(() -> service.setAssetTags(ASSET_ID, tooMany, OWNER_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("tagsOf：按字典名回查并排序")
    void tagsOfShouldReturnSortedNames() {
        AssetTagEntity relation = new AssetTagEntity();
        relation.setAssetId(ASSET_ID);
        relation.setTagId(9L);
        when(assetTagMapper.selectList(any(Wrapper.class))).thenReturn(List.of(relation));
        TagEntity tag = new TagEntity();
        tag.setId(9L);
        tag.setName("spring");
        when(tagMapper.selectList(any(Wrapper.class))).thenReturn(List.of(tag));

        List<String> names = service.tagsOf(ASSET_ID);

        assertThat(names).containsExactly("spring");
    }
}
