package com.aihub.community.service.impl;

import com.aihub.api.dto.IssueQueryItem;
import com.aihub.api.dto.PostQueryItem;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.entity.IssueEntity;
import com.aihub.community.entity.PostEntity;
import com.aihub.community.enums.IssueStatus;
import com.aihub.community.enums.PostType;
import com.aihub.community.mapper.IssueMapper;
import com.aihub.community.mapper.PostMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 内部社区只读查询。
 *
 * <p>重点在参数校验：模型生成的 status / type 非法时必须明确报错，而不是「当作没传」返回全部内容，
 * 否则 Agent 会把「筛错了」当成「确实只有这些」。
 */
@ExtendWith(MockitoExtension.class)
class CommunityQueryServiceImplTest {

    private static final long ASSET_ID = 100L;

    @Mock
    private IssueMapper issueMapper;
    @Mock
    private PostMapper postMapper;

    private CommunityQueryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, IssueEntity.class);
        TableInfoHelper.initTableInfo(assistant, PostEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new CommunityQueryServiceImpl(issueMapper, postMapper);
    }

    @Test
    @DisplayName("Issue 查询：assetId 必填，缺失直接拒绝")
    void listIssuesShouldRequireAssetId() {
        assertThatThrownBy(() -> service.listIssues(null, null, new PageQuery(1, 10)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("Issue 查询：非法状态直接拒绝，不能当成「不限状态」")
    void listIssuesShouldRejectUnknownStatus() {
        assertThatThrownBy(() -> service.listIssues(ASSET_ID, "BOGUS", new PageQuery(1, 10)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("Issue 查询：状态容错大小写，并映射为契约字段")
    void listIssuesShouldFilterAndMap() {
        IssueEntity entity = new IssueEntity();
        entity.setId(1L);
        entity.setAssetId(ASSET_ID);
        entity.setVersion("1.0.0");
        entity.setTitle("启动报错");
        entity.setContent("依赖缺失");
        entity.setStatus(IssueStatus.PROCESSING.name());
        Page<IssueEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(entity));
        page.setTotal(1);
        when(issueMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        PageResult<IssueQueryItem> result = service.listIssues(ASSET_ID, " processing ", new PageQuery(1, 10));

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).status()).isEqualTo(IssueStatus.PROCESSING.name());
        assertThat(result.total()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<IssueEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper).selectPage(any(Page.class), captor.capture());
        assertThat(boundParams(captor.getValue()).values())
                .contains(ASSET_ID, IssueStatus.PROCESSING.name());
    }

    @Test
    @DisplayName("Issue 查询：不传状态时不追加状态条件")
    void listIssuesShouldSkipBlankStatus() {
        when(issueMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());

        service.listIssues(ASSET_ID, "  ", new PageQuery(1, 10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<IssueEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper).selectPage(any(Page.class), captor.capture());
        assertThat(boundParams(captor.getValue())).containsValue(ASSET_ID);
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("status");
    }

    /**
     * 取 wrapper 上绑定的参数值。
     *
     * <p>必须先调 {@code getSqlSegment()}：MyBatis-Plus 是在生成 SQL 片段时才把条件参数
     * 放进 paramNameValuePairs，直接读会得到空 Map。
     */
    private static Map<String, Object> boundParams(Wrapper<?> wrapper) {
        wrapper.getSqlSegment();
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    @Test
    @DisplayName("帖子查询：非法类型直接拒绝")
    void listPostsShouldRejectUnknownType() {
        assertThatThrownBy(() -> service.listPosts("BOGUS", new PageQuery(1, 10)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("帖子查询：按类型过滤并映射契约字段")
    void listPostsShouldFilterAndMap() {
        PostEntity entity = new PostEntity();
        entity.setId(9L);
        entity.setAuthorId(7L);
        entity.setType(PostType.GUIDE.name());
        entity.setTitle("如何接入 Spring AI");
        entity.setContent("正文");
        entity.setAssetId(ASSET_ID);
        entity.setLikeCount(3L);
        Page<PostEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(entity));
        page.setTotal(1);
        when(postMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        PageResult<PostQueryItem> result = service.listPosts("guide", new PageQuery(1, 10));

        assertThat(result.records().get(0).type()).isEqualTo(PostType.GUIDE.name());
        assertThat(result.records().get(0).likeCount()).isEqualTo(3L);
    }
}
