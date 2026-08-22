package com.aihub.community.service.impl;

import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.redis.RedisKeys;
import com.aihub.community.entity.PostLikeEntity;
import com.aihub.community.mapper.PostLikeMapper;
import com.aihub.community.service.PostLikeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 点赞实现。
 *
 * <p>MySQL 是唯一事实来源，Redis 只是可重建的派生数据：Redis Set 抢到不等于成功，MySQL 插入成功才算；
 * 两边不一致时以 MySQL 为准并顺手修正 Redis。
 */
@Service
public class PostLikeServiceImpl implements PostLikeService {

    private static final Logger log = LoggerFactory.getLogger(PostLikeServiceImpl.class);

    private final PostLikeMapper postLikeMapper;
    private final StringRedisTemplate redisTemplate;
    private final Duration dedupTtl;

    public PostLikeServiceImpl(PostLikeMapper postLikeMapper,
                              StringRedisTemplate redisTemplate,
                              @Value("${aihub.community.like.dedup-ttl:30d}") Duration dedupTtl) {
        this.postLikeMapper = postLikeMapper;
        this.redisTemplate = redisTemplate;
        this.dedupTtl = dedupTtl;
    }

    @Override
    @Transactional
    public boolean like(Long postId, Long userId) {
        if (postId == null || userId == null) {
            // 没有 userId 就无法去重，这种点赞不予接受
            return false;
        }
        String key = RedisKeys.postLikeSet(postId);
        String member = String.valueOf(userId);

        Long added = redisTemplate.opsForSet().add(key, member);
        ensureTtl(key);
        if (added == null || added == 0L) {
            // 快路径：Redis 说已赞过，直接返回，省一次数据库往返
            return false;
        }

        try {
            PostLikeEntity entity = new PostLikeEntity();
            entity.setPostId(postId);
            entity.setUserId(userId);
            entity.setDeleted(BaseEntity.NOT_DELETED);
            postLikeMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            // Redis 丢过数据导致两边不一致：以 MySQL 为准，并修正 Redis 的偏差
            log.warn("like already exists in db but not in redis cache, correcting cache, postId={}, userId={}",
                    postId, userId);
            redisTemplate.opsForSet().remove(key, member);
            return false;
        }
    }

    @Override
    @Transactional
    public boolean unlike(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        PostLikeEntity existing = findActive(postId, userId);
        if (existing == null) {
            // 没点过赞：顺手清掉可能残留的缓存成员，保持两边一致
            redisTemplate.opsForSet().remove(RedisKeys.postLikeSet(postId), String.valueOf(userId));
            return false;
        }

        // 先改 MySQL 再改 Redis：MySQL 失败时异常上抛，Redis 保持原样，不会出现"缓存说没赞、库里说赞了"
        PostLikeEntity update = new PostLikeEntity();
        update.setId(existing.getId());
        update.setDeleted(System.currentTimeMillis());
        postLikeMapper.updateById(update);

        redisTemplate.opsForSet().remove(RedisKeys.postLikeSet(postId), String.valueOf(userId));
        return true;
    }

    @Override
    public boolean hasLiked(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        return findActive(postId, userId) != null;
    }

    @Override
    public long likeCount(Long postId) {
        if (postId == null) {
            return 0L;
        }
        Long count = postLikeMapper.selectCount(new LambdaQueryWrapper<PostLikeEntity>()
                .eq(PostLikeEntity::getPostId, postId)
                .eq(PostLikeEntity::getDeleted, BaseEntity.NOT_DELETED));
        return count == null ? 0L : count;
    }

    private PostLikeEntity findActive(Long postId, Long userId) {
        return postLikeMapper.selectOne(new LambdaQueryWrapper<PostLikeEntity>()
                .eq(PostLikeEntity::getPostId, postId)
                .eq(PostLikeEntity::getUserId, userId)
                .eq(PostLikeEntity::getDeleted, BaseEntity.NOT_DELETED));
    }

    /** 仅在集合还没有 TTL 时设置，避免随访问无限续命（docs/DATA.md §3 禁止永久膨胀的 Key） */
    private void ensureTtl(String key) {
        Long current = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (current == null || current < 0) {
            redisTemplate.expire(key, dedupTtl);
        }
    }
}
