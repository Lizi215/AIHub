package com.aihub.community.service.impl;

import com.aihub.common.mybatis.BaseEntity;
import com.aihub.community.entity.PostFavoriteEntity;
import com.aihub.community.mapper.PostFavoriteMapper;
import com.aihub.community.service.PostFavoriteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收藏实现。
 *
 * <p>去重完全依赖 MySQL 唯一索引 {@code uk_post_favorite(post_id, user_id, deleted)}：
 * 收藏不是高频写，没有必要再引入 Redis 缓存与随之而来的一致性问题。
 * 唯一索引含 deleted 才能支持「取消收藏后重新收藏」。
 */
@Service
public class PostFavoriteServiceImpl implements PostFavoriteService {

    private final PostFavoriteMapper favoriteMapper;

    public PostFavoriteServiceImpl(PostFavoriteMapper favoriteMapper) {
        this.favoriteMapper = favoriteMapper;
    }

    @Override
    @Transactional
    public boolean favorite(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        PostFavoriteEntity entity = new PostFavoriteEntity();
        entity.setPostId(postId);
        entity.setUserId(userId);
        entity.setDeleted(BaseEntity.NOT_DELETED);
        try {
            favoriteMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            // 已收藏：唯一索引兜住并发，直接当重复处理
            return false;
        }
    }

    @Override
    @Transactional
    public boolean unfavorite(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        PostFavoriteEntity existing = findActive(postId, userId);
        if (existing == null) {
            return false;
        }
        // 软删除写时间戳，才能让"取消后再收藏"不撞唯一键
        PostFavoriteEntity update = new PostFavoriteEntity();
        update.setId(existing.getId());
        update.setDeleted(System.currentTimeMillis());
        favoriteMapper.updateById(update);
        return true;
    }

    @Override
    public boolean hasFavorited(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        return findActive(postId, userId) != null;
    }

    private PostFavoriteEntity findActive(Long postId, Long userId) {
        return favoriteMapper.selectOne(new LambdaQueryWrapper<PostFavoriteEntity>()
                .eq(PostFavoriteEntity::getPostId, postId)
                .eq(PostFavoriteEntity::getUserId, userId)
                .eq(PostFavoriteEntity::getDeleted, BaseEntity.NOT_DELETED));
    }
}
