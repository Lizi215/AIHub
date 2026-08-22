package com.aihub.community.service.impl;

import com.aihub.common.auth.Role;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.trace.UserContext;
import com.aihub.community.dto.CreateIssueRequest;
import com.aihub.community.entity.IssueEntity;
import com.aihub.community.enums.IssueStatus;
import com.aihub.community.mapper.IssueMapper;
import com.aihub.community.support.AuthorNameResolver;
import com.aihub.community.vo.IssueVo;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
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
 * Issue 服务：状态机落地与权限判定。
 *
 * <p>权限覆盖两条依据：作者走归属，跨归属处理走角色。
 */
@ExtendWith(MockitoExtension.class)
class IssueServiceImplTest {

    private static final long ISSUE_ID = 900L;
    private static final long ASSET_ID = 100L;
    private static final long AUTHOR_ID = 1L;
    private static final long OTHER_ID = 2L;

    @Mock
    private IssueMapper issueMapper;
    @Mock
    private AuthorNameResolver authorNameResolver;

    private IssueServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IssueServiceImpl(issueMapper, authorNameResolver);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("创建 Issue：初始状态 OPEN，归属为提出者")
    void shouldCreateWithOpenStatus() {
        service.create(AUTHOR_ID, new CreateIssueRequest(ASSET_ID, "1.0.0", "crash on start", "details"));

        ArgumentCaptor<IssueEntity> captor = ArgumentCaptor.forClass(IssueEntity.class);
        verify(issueMapper).insert(captor.capture());
        IssueEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(IssueStatus.OPEN.name());
        assertThat(saved.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getAssetId()).isEqualTo(ASSET_ID);
        assertThat(saved.getDeleted()).isZero();
    }

    @Test
    @DisplayName("作者可推进状态：OPEN → PROCESSING")
    void shouldAllowAuthorToAdvance() {
        when(issueMapper.selectOne(any())).thenReturn(issue(IssueStatus.OPEN, AUTHOR_ID));

        IssueVo vo = service.changeStatus(ISSUE_ID, AUTHOR_ID, "processing");

        assertThat(vo.status()).isEqualTo(IssueStatus.PROCESSING.name());
        ArgumentCaptor<IssueEntity> captor = ArgumentCaptor.forClass(IssueEntity.class);
        verify(issueMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IssueStatus.PROCESSING.name());
    }

    @Test
    @DisplayName("非法流转被拒绝：PROCESSING 不能退回 OPEN")
    void shouldRejectIllegalTransition() {
        when(issueMapper.selectOne(any())).thenReturn(issue(IssueStatus.PROCESSING, AUTHOR_ID));

        assertThatThrownBy(() -> service.changeStatus(ISSUE_ID, AUTHOR_ID, "OPEN"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ISSUE_STATUS_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("原地不动也被拒绝：不能把同状态当作一次成功流转")
    void shouldRejectSelfTransition() {
        when(issueMapper.selectOne(any())).thenReturn(issue(IssueStatus.OPEN, AUTHOR_ID));

        assertThatThrownBy(() -> service.changeStatus(ISSUE_ID, AUTHOR_ID, "OPEN"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ISSUE_STATUS_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("非作者且非特权角色不能处理：走角色而不是归属")
    void shouldRejectNonAuthorWithoutPrivilege() {
        when(issueMapper.selectOne(any())).thenReturn(issue(IssueStatus.OPEN, AUTHOR_ID));
        UserContext.set(OTHER_ID, Role.USER.name());

        assertThatThrownBy(() -> service.changeStatus(ISSUE_ID, OTHER_ID, "CLOSED"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("Maintainer 可跨归属处理任意 Issue")
    void shouldAllowMaintainerToHandleOthersIssue() {
        when(issueMapper.selectOne(any())).thenReturn(issue(IssueStatus.OPEN, AUTHOR_ID));
        UserContext.set(OTHER_ID, Role.MAINTAINER.name());

        IssueVo vo = service.changeStatus(ISSUE_ID, OTHER_ID, "CLOSED");

        assertThat(vo.status()).isEqualTo(IssueStatus.CLOSED.name());
    }

    @Test
    @DisplayName("Issue 不存在返回 30002")
    void shouldFailWhenIssueMissing() {
        when(issueMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.changeStatus(ISSUE_ID, AUTHOR_ID, "CLOSED"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ISSUE_NOT_FOUND);
    }

    @Test
    @DisplayName("未知状态字符串明确报参数错误，不能悄悄给默认值")
    void shouldRejectUnknownStatusValue() {
        when(issueMapper.selectOne(any())).thenReturn(issue(IssueStatus.OPEN, AUTHOR_ID));

        assertThatThrownBy(() -> service.changeStatus(ISSUE_ID, AUTHOR_ID, "WHATEVER"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("Issue 列表填充作者昵称")
    void shouldFillAuthorNameInList() {
        Page<IssueEntity> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(issue(IssueStatus.OPEN, AUTHOR_ID)));
        when(issueMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);
        when(authorNameResolver.resolve(any())).thenReturn(Map.of(AUTHOR_ID, "小明"));

        PageResult<IssueVo> result = service.listByAsset(ASSET_ID, new PageQuery(1, 10));

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).authorName()).isEqualTo("小明");
    }

    private IssueEntity issue(IssueStatus status, Long authorId) {
        IssueEntity entity = new IssueEntity();
        entity.setId(ISSUE_ID);
        entity.setAuthorId(authorId);
        entity.setAssetId(ASSET_ID);
        entity.setStatus(status.name());
        entity.setDeleted(0L);
        return entity;
    }
}
