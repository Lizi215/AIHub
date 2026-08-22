package com.aihub.community.service;

/** 帖子收藏。 */
public interface PostFavoriteService {

    /**
     * 收藏。
     *
     * @return {@code true} 表示本次真正新增；{@code false} 表示已收藏过，已忽略
     */
    boolean favorite(Long postId, Long userId);

    /** 取消收藏。 */
    boolean unfavorite(Long postId, Long userId);

    /** 是否已收藏。 */
    boolean hasFavorited(Long postId, Long userId);
}
