package com.aihub.community.service.impl;

import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreatePostRequest;
import com.aihub.community.entity.PostEntity;
import com.aihub.community.enums.PostType;
import com.aihub.community.mapper.PostMapper;
import com.aihub.community.service.OutboxEventService;
import com.aihub.community.service.PostService;
import com.aihub.community.vo.PostVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 帖子实现。
 */
@Service
public class PostServiceImpl implements PostService {

    private static final Logger log = LoggerFactory.getLogger(PostServiceImpl.class);

    private final PostMapper postMapper;
    private final OutboxEventService outboxEventService;

    public PostServiceImpl(PostMapper postMapper, OutboxEventService outboxEventService) {
        this.postMapper = postMapper;
        this.outboxEventService = outboxEventService;
    }

    @Override
    @Transactional
    public Long create(Long authorId, CreatePostRequest request) {
        // 类型非法直接拒绝，不能落到默认类型——那会让帖子出现在错误的分类里
        PostType type = PostType.parse(request.type());

        PostEntity entity = new PostEntity();
        entity.setAuthorId(authorId);
        entity.setType(type.name());
        entity.setTitle(request.title());
        entity.setContent(request.content());
        entity.setAssetId(request.assetId());
        entity.setLikeCount(0L);
        entity.setDeleted(BaseEntity.NOT_DELETED);
        postMapper.insert(entity);

        // PostCreatedEvent → ES 索引同步（docs/MESSAGING.md §1）。
        // 事件与帖子在同一事务内落库，提交后再投递；ES 不可用不会影响发帖。
        outboxEventService.appendPostCreated(entity);

        log.info("post created, postId={}, type={}, authorId={}", entity.getId(), type, authorId);
        return entity.getId();
    }

    @Override
    public PostVo detail(Long postId) {
        return toVo(requirePost(postId));
    }

    @Override
    public PageResult<PostVo> list(String type, PageQuery query) {
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getDeleted, BaseEntity.NOT_DELETED);
        if (type != null && !type.isBlank()) {
            // 列表筛选同样走枚举解析，非法类型不静默返回空列表而是明确报错
            wrapper.eq(PostEntity::getType, PostType.parse(type).name());
        }
        wrapper.orderByDesc(PostEntity::getCreateTime);

        Page<PostEntity> page = postMapper.selectPage(
                new Page<>(query.pageOrDefault(), query.sizeOrDefault()), wrapper);

        List<PostVo> records = page.getRecords().stream().map(this::toVo).toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private PostEntity requirePost(Long postId) {
        PostEntity post = postMapper.selectOne(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getId, postId)
                .eq(PostEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (post == null) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    private PostVo toVo(PostEntity entity) {
        return new PostVo(entity.getId(), entity.getAuthorId(), entity.getType(), entity.getTitle(),
                entity.getContent(), entity.getAssetId(), entity.getLikeCount(), entity.getCreateTime());
    }
}
