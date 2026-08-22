package com.aihub.community.service.impl;

import com.aihub.community.entity.PostFavoriteEntity;
import com.aihub.community.mapper.PostFavoriteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 收藏：唯一索引去重与软删除。 */
@ExtendWith(MockitoExtension.class)
class PostFavoriteServiceImplTest {

    private static final long POST_ID = 700L;
    private static final long USER_ID = 7L;

    @Mock
    private PostFavoriteMapper favoriteMapper;

    private PostFavoriteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PostFavoriteServiceImpl(favoriteMapper);
    }

    @Test
    @DisplayName("首次收藏成功")
    void shouldFavoriteFirstTime() {
        assertThat(service.favorite(POST_ID, USER_ID)).isTrue();

        ArgumentCaptor<PostFavoriteEntity> captor = ArgumentCaptor.forClass(PostFavoriteEntity.class);
        verify(favoriteMapper).insert(captor.capture());
        assertThat(captor.getValue().getDeleted()).isZero();
    }

    @Test
    @DisplayName("重复收藏由唯一索引兜住，当成重复而非报错")
    void shouldTreatDuplicateAsAlreadyFavorited() {
        when(favoriteMapper.insert(any(PostFavoriteEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_post_favorite"));

        assertThat(service.favorite(POST_ID, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("取消收藏写删除时间戳，否则重新收藏会撞唯一键")
    void shouldUnfavoriteWithSoftDelete() {
        PostFavoriteEntity existing = new PostFavoriteEntity();
        existing.setId(9L);
        when(favoriteMapper.selectOne(any())).thenReturn(existing);

        assertThat(service.unfavorite(POST_ID, USER_ID)).isTrue();

        ArgumentCaptor<PostFavoriteEntity> captor = ArgumentCaptor.forClass(PostFavoriteEntity.class);
        verify(favoriteMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDeleted()).isNotNull().isGreaterThan(0L);
    }

    @Test
    @DisplayName("取消未收藏的帖子返回 false，且不动数据库")
    void shouldReturnFalseWhenNotFavorited() {
        when(favoriteMapper.selectOne(any())).thenReturn(null);

        assertThat(service.unfavorite(POST_ID, USER_ID)).isFalse();

        verify(favoriteMapper, never()).updateById(any(PostFavoriteEntity.class));
    }

    @Test
    @DisplayName("缺少 userId 时不予接受")
    void shouldRejectWithoutUserId() {
        assertThat(service.favorite(POST_ID, null)).isFalse();

        verify(favoriteMapper, never()).insert(any(PostFavoriteEntity.class));
    }
}
