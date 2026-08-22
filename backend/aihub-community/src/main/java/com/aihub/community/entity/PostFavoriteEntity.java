package com.aihub.community.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 帖子收藏。
 *
 * <p>与点赞不同，收藏不做 Redis 去重集合：收藏不是高频写操作，
 * 唯一索引已足够，少一个需要维护一致性的缓存。
 */
@TableName("post_favorite")
public class PostFavoriteEntity extends BaseEntity {

    private Long postId;

    private Long userId;

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
