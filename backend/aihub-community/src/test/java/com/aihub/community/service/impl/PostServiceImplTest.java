package com.aihub.community.service.impl;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreatePostRequest;
import com.aihub.community.entity.PostEntity;
import com.aihub.community.mapper.PostMapper;
import com.aihub.community.service.OutboxEventService;
import com.aihub.community.vo.PostVo;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 帖子服务：类型解析、落库字段与读取。
 */
@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    private static final long AUTHOR_ID = 1L;
    private static final long POST_ID = 700L;

    @Mock
    private PostMapper postMapper;
    @Mock
    private OutboxEventService outboxEventService;

    private PostServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PostServiceImpl(postMapper, outboxEventService);
    }

    @Test
    @DisplayName("发帖：类型归一化，点赞数初始为 0，归属为作者")
    void shouldCreatePost() {
        service.create(AUTHOR_ID, new CreatePostRequest("guide", "how to build a skill", "content", null));

        ArgumentCaptor<PostEntity> captor = ArgumentCaptor.forClass(PostEntity.class);
        verify(postMapper).insert(captor.capture());
        PostEntity saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("GUIDE");
        assertThat(saved.getLikeCount()).isZero();
        assertThat(saved.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getDeleted()).isZero();
        // 发帖必须同时写 PostCreatedEvent（docs/MESSAGING.md §1），否则帖子永远进不了索引
        verify(outboxEventService).appendPostCreated(saved);
    }

    @Test
    @DisplayName("非法帖子类型被拒绝，且不落库（不能悄悄落到默认分类）")
    void shouldRejectUnknownType() {
        assertThatThrownBy(() -> service.create(AUTHOR_ID, new CreatePostRequest("BLOG", "t", "c", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        verify(postMapper, never()).insert(any(PostEntity.class));
        // 类型非法时连事件都不该写：否则索引里会出现一条不存在的帖子
        verify(outboxEventService, never()).appendPostCreated(any());
    }

    @Test
    @DisplayName("帖子不存在返回 30001")
    void shouldFailWhenPostMissing() {
        when(postMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.detail(POST_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
    }

    @Test
    @DisplayName("列表筛选的类型非法时报参数错误，而不是静默返回空列表")
    void shouldRejectUnknownTypeOnList() {
        assertThatThrownBy(() -> service.list("NOPE", new PageQuery(1, 10)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("列表：分页参数透传并映射为统一分页契约")
    void shouldMapPageResult() {
        PostEntity post = new PostEntity();
        post.setId(POST_ID);
        post.setAuthorId(AUTHOR_ID);
        post.setType("QUESTION");
        post.setTitle("t");
        post.setLikeCount(2L);
        Page<PostEntity> page = new Page<>(2, 10);
        page.setTotal(21);
        page.setRecords(List.of(post));
        when(postMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);

        PageResult<PostVo> result = service.list(null, new PageQuery(2, 10));

        assertThat(result.total()).isEqualTo(21);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).type()).isEqualTo("QUESTION");
        assertThat(result.records().get(0).likeCount()).isEqualTo(2L);
    }
}
