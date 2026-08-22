package com.aihub.community.service.impl;

import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreateCommentRequest;
import com.aihub.community.entity.PostCommentEntity;
import com.aihub.community.entity.PostEntity;
import com.aihub.community.mapper.PostCommentMapper;
import com.aihub.community.mapper.PostMapper;
import com.aihub.community.service.PostCommentService;
import com.aihub.community.vo.PostCommentVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 评论实现。 */
@Service
public class PostCommentServiceImpl implements PostCommentService {

    private final PostCommentMapper commentMapper;
    private final PostMapper postMapper;

    public PostCommentServiceImpl(PostCommentMapper commentMapper, PostMapper postMapper) {
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
    }

    @Override
    @Transactional
    public Long create(Long postId, Long authorId, CreateCommentRequest request) {
        // 先确认帖子存在，否则会留下挂在不存在帖子下的孤儿评论
        PostEntity post = postMapper.selectOne(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getId, postId)
                .eq(PostEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (post == null) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }

        PostCommentEntity entity = new PostCommentEntity();
        entity.setPostId(postId);
        entity.setAuthorId(authorId);
        entity.setContent(request.content());
        entity.setDeleted(BaseEntity.NOT_DELETED);
        commentMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public PageResult<PostCommentVo> listByPost(Long postId, PageQuery query) {
        // 按时间升序，符合对话阅读顺序
        Page<PostCommentEntity> page = commentMapper.selectPage(
                new Page<>(query.pageOrDefault(), query.sizeOrDefault()),
                new LambdaQueryWrapper<PostCommentEntity>()
                        .eq(PostCommentEntity::getPostId, postId)
                        .eq(PostCommentEntity::getDeleted, BaseEntity.NOT_DELETED)
                        .orderByAsc(PostCommentEntity::getCreateTime));
        return PageResult.of(page.getRecords().stream()
                        .map(c -> new PostCommentVo(c.getId(), c.getPostId(), c.getAuthorId(),
                                c.getContent(), c.getCreateTime()))
                        .toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }
}
