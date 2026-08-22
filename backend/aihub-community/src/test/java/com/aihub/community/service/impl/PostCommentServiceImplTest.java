package com.aihub.community.service.impl;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreateCommentRequest;
import com.aihub.community.entity.PostCommentEntity;
import com.aihub.community.entity.PostEntity;
import com.aihub.community.mapper.PostCommentMapper;
import com.aihub.community.mapper.PostMapper;
import com.aihub.community.vo.PostCommentVo;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 评论：帖子存在性校验与列表映射。 */
@ExtendWith(MockitoExtension.class)
class PostCommentServiceImplTest {

    private static final long POST_ID = 700L;
    private static final long AUTHOR_ID = 1L;

    @Mock
    private PostCommentMapper commentMapper;
    @Mock
    private PostMapper postMapper;

    private PostCommentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PostCommentServiceImpl(commentMapper, postMapper);
    }

    @Test
    @DisplayName("帖子不存在时拒绝评论，不产生挂在不存在帖子下的孤儿评论")
    void shouldRejectCommentWhenPostMissing() {
        when(postMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create(POST_ID, AUTHOR_ID, new CreateCommentRequest("hi")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.POST_NOT_FOUND);

        verify(commentMapper, never()).insert(any(PostCommentEntity.class));
    }

    @Test
    @DisplayName("评论落库字段正确")
    void shouldCreateComment() {
        when(postMapper.selectOne(any())).thenReturn(new PostEntity());

        service.create(POST_ID, AUTHOR_ID, new CreateCommentRequest("nice work"));

        ArgumentCaptor<PostCommentEntity> captor = ArgumentCaptor.forClass(PostCommentEntity.class);
        verify(commentMapper).insert(captor.capture());
        assertThat(captor.getValue().getPostId()).isEqualTo(POST_ID);
        assertThat(captor.getValue().getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(captor.getValue().getDeleted()).isZero();
    }

    @Test
    @DisplayName("评论列表按分页契约映射")
    void shouldListComments() {
        PostCommentEntity comment = new PostCommentEntity();
        comment.setId(1L);
        comment.setPostId(POST_ID);
        comment.setAuthorId(AUTHOR_ID);
        comment.setContent("c");
        comment.setCreateTime(LocalDateTime.now());
        Page<PostCommentEntity> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(comment));
        when(commentMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);

        PageResult<PostCommentVo> result = service.listByPost(POST_ID, new PageQuery(1, 10));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).content()).isEqualTo("c");
    }
}
