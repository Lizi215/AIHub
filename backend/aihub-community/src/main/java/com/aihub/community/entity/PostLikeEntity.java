package com.aihub.community.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 帖子点赞表映射（`post_like`）。
 *
 * <p>{@code uk_post_like(post_id, user_id, deleted)} 是「重复点赞不重复计」的硬保证。
 * 唯一索引包含 {@code deleted} 是为了让「取消点赞后再点赞」不会撞唯一键——
 * 若用 0/1 双态，取消后重新点赞就会被拦住（docs/DATA.md §2）。
 */
@TableName("post_like")
public class PostLikeEntity extends BaseEntity {

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
