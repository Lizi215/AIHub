package com.aihub.community.service;

/**
 * 帖子点赞。
 *
 * <p>「重复点赞不重复计」由两层保证：Redis Set 是快路径，命中即返回；MySQL 的
 * {@code uk_post_like} 唯一索引是硬保证，Redis 丢了也不会重复计数（Redis 只是派生数据）。
 */
public interface PostLikeService {

    /**
     * 点赞。
     *
     * @return {@code true} 表示本次真正新增点赞；{@code false} 表示已点过赞，已忽略
     */
    boolean like(Long postId, Long userId);

    /**
     * 取消点赞（软删除）。
     *
     * @return {@code true} 表示确实取消了；{@code false} 表示本来就没点过
     */
    boolean unlike(Long postId, Long userId);

    /** 是否已点赞。以 MySQL 为准。 */
    boolean hasLiked(Long postId, Long userId);

    /** 点赞数。以 MySQL 为准，避免 Redis 缓存漂移影响展示。 */
    long likeCount(Long postId);
}
