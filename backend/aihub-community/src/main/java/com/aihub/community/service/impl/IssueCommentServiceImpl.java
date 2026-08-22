package com.aihub.community.service.impl;

import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreateCommentRequest;
import com.aihub.community.entity.IssueCommentEntity;
import com.aihub.community.entity.IssueEntity;
import com.aihub.community.mapper.IssueCommentMapper;
import com.aihub.community.mapper.IssueMapper;
import com.aihub.community.service.IssueCommentService;
import com.aihub.community.support.AuthorNameResolver;
import com.aihub.community.vo.IssueCommentVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Issue 评论实现。 */
@Service
public class IssueCommentServiceImpl implements IssueCommentService {

    private final IssueCommentMapper commentMapper;
    private final IssueMapper issueMapper;
    private final AuthorNameResolver authorNameResolver;

    public IssueCommentServiceImpl(IssueCommentMapper commentMapper, IssueMapper issueMapper,
                                   AuthorNameResolver authorNameResolver) {
        this.commentMapper = commentMapper;
        this.issueMapper = issueMapper;
        this.authorNameResolver = authorNameResolver;
    }

    @Override
    @Transactional
    public Long create(Long issueId, Long authorId, CreateCommentRequest request) {
        // 先确认 Issue 存在，否则会留下挂在不存在 Issue 下的孤儿评论
        IssueEntity issue = issueMapper.selectOne(new LambdaQueryWrapper<IssueEntity>()
                .eq(IssueEntity::getId, issueId)
                .eq(IssueEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (issue == null) {
            throw new BizException(ErrorCode.ISSUE_NOT_FOUND);
        }

        IssueCommentEntity entity = new IssueCommentEntity();
        entity.setIssueId(issueId);
        entity.setAuthorId(authorId);
        entity.setContent(request.content());
        entity.setDeleted(BaseEntity.NOT_DELETED);
        commentMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public PageResult<IssueCommentVo> listByIssue(Long issueId, PageQuery query) {
        // 按时间升序，讨论要按对话顺序读
        Page<IssueCommentEntity> page = commentMapper.selectPage(
                new Page<>(query.pageOrDefault(), query.sizeOrDefault()),
                new LambdaQueryWrapper<IssueCommentEntity>()
                        .eq(IssueCommentEntity::getIssueId, issueId)
                        .eq(IssueCommentEntity::getDeleted, BaseEntity.NOT_DELETED)
                        .orderByAsc(IssueCommentEntity::getCreateTime));
        List<IssueCommentEntity> records = page.getRecords();
        Map<Long, String> names = authorNameResolver.resolve(
                records.stream().map(IssueCommentEntity::getAuthorId).toList());
        return PageResult.of(records.stream()
                        .map(c -> new IssueCommentVo(c.getId(), c.getIssueId(), c.getAuthorId(),
                                c.getContent(), c.getCreateTime(), names.get(c.getAuthorId())))
                        .toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }
}
