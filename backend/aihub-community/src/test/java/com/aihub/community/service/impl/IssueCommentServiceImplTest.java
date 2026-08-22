package com.aihub.community.service.impl;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreateCommentRequest;
import com.aihub.community.entity.IssueCommentEntity;
import com.aihub.community.entity.IssueEntity;
import com.aihub.community.mapper.IssueCommentMapper;
import com.aihub.community.mapper.IssueMapper;
import com.aihub.community.support.AuthorNameResolver;
import com.aihub.community.vo.IssueCommentVo;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Issue 评论：Issue 存在性校验与列表映射。 */
@ExtendWith(MockitoExtension.class)
class IssueCommentServiceImplTest {

    private static final long ISSUE_ID = 900L;
    private static final long AUTHOR_ID = 7L;

    @Mock
    private IssueCommentMapper commentMapper;
    @Mock
    private IssueMapper issueMapper;
    @Mock
    private AuthorNameResolver authorNameResolver;

    private IssueCommentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IssueCommentServiceImpl(commentMapper, issueMapper, authorNameResolver);
    }

    @Test
    @DisplayName("Issue 不存在时拒绝评论，不产生挂在不存在 Issue 下的孤儿评论")
    void shouldRejectCommentWhenIssueMissing() {
        when(issueMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create(ISSUE_ID, AUTHOR_ID, new CreateCommentRequest("hi")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ISSUE_NOT_FOUND);

        verify(commentMapper, never()).insert(any(IssueCommentEntity.class));
    }

    @Test
    @DisplayName("评论落库字段正确，且 deleted 为未删除")
    void shouldCreateComment() {
        when(issueMapper.selectOne(any())).thenReturn(new IssueEntity());

        service.create(ISSUE_ID, AUTHOR_ID, new CreateCommentRequest("这个版本的 README 少了一步"));

        ArgumentCaptor<IssueCommentEntity> captor = ArgumentCaptor.forClass(IssueCommentEntity.class);
        verify(commentMapper).insert(captor.capture());
        IssueCommentEntity saved = captor.getValue();
        assertThat(saved.getIssueId()).isEqualTo(ISSUE_ID);
        assertThat(saved.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getContent()).isEqualTo("这个版本的 README 少了一步");
        assertThat(saved.getDeleted()).isZero();
    }

    @Test
    @DisplayName("评论列表按分页契约映射")
    void shouldListComments() {
        IssueCommentEntity comment = new IssueCommentEntity();
        comment.setId(1L);
        comment.setIssueId(ISSUE_ID);
        comment.setAuthorId(AUTHOR_ID);
        comment.setContent("c");
        comment.setCreateTime(LocalDateTime.now());
        Page<IssueCommentEntity> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(comment));
        when(commentMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);
        when(authorNameResolver.resolve(any())).thenReturn(Map.of(AUTHOR_ID, "小明"));

        PageResult<IssueCommentVo> result = service.listByIssue(ISSUE_ID, new PageQuery(1, 10));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).issueId()).isEqualTo(ISSUE_ID);
        assertThat(result.records().get(0).content()).isEqualTo("c");
        assertThat(result.records().get(0).authorName()).isEqualTo("小明");
    }

    @Test
    @DisplayName("作者昵称取不到时 authorName 为 null，交给前端兜底，不影响列表返回")
    void shouldTolerateMissingAuthorName() {
        IssueCommentEntity comment = new IssueCommentEntity();
        comment.setId(1L);
        comment.setIssueId(ISSUE_ID);
        comment.setAuthorId(AUTHOR_ID);
        comment.setContent("c");
        comment.setCreateTime(LocalDateTime.now());
        Page<IssueCommentEntity> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(comment));
        when(commentMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);
        when(authorNameResolver.resolve(any())).thenReturn(Map.of());

        PageResult<IssueCommentVo> result = service.listByIssue(ISSUE_ID, new PageQuery(1, 10));

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).authorName()).isNull();
    }
}
