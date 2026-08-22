package com.aihub.community.service.impl;

import com.aihub.common.auth.Role;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.trace.UserContext;
import com.aihub.community.domain.IssueStatusMachine;
import com.aihub.community.dto.CreateIssueRequest;
import com.aihub.community.entity.IssueEntity;
import com.aihub.community.enums.IssueStatus;
import com.aihub.community.mapper.IssueMapper;
import com.aihub.community.service.IssueService;
import com.aihub.community.support.AuthorNameResolver;
import com.aihub.community.vo.IssueVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Issue 实现。
 *
 * <p>权限两条依据（docs/ARCHITECTURE.md §9.2）：管理自己名下的内容看归属，作者即可；
 * 处理他人 Issue 才需要 Maintainer 及以上。
 */
@Service
public class IssueServiceImpl implements IssueService {

    private static final Logger log = LoggerFactory.getLogger(IssueServiceImpl.class);

    private final IssueMapper issueMapper;
    private final AuthorNameResolver authorNameResolver;

    public IssueServiceImpl(IssueMapper issueMapper, AuthorNameResolver authorNameResolver) {
        this.issueMapper = issueMapper;
        this.authorNameResolver = authorNameResolver;
    }

    @Override
    @Transactional
    public Long create(Long authorId, CreateIssueRequest request) {
        IssueEntity entity = new IssueEntity();
        entity.setAuthorId(authorId);
        entity.setAssetId(request.assetId());
        entity.setVersion(request.version());
        entity.setTitle(request.title());
        entity.setContent(request.content());
        // 新建一律 OPEN，不经状态机——状态机管的是「变更」，不是初始值
        entity.setStatus(IssueStatus.OPEN.name());
        entity.setDeleted(BaseEntity.NOT_DELETED);
        issueMapper.insert(entity);
        log.info("issue created, issueId={}, assetId={}, authorId={}",
                entity.getId(), request.assetId(), authorId);
        return entity.getId();
    }

    @Override
    public IssueVo detail(Long issueId) {
        return toVo(requireIssue(issueId));
    }
    @Override
    @Transactional
    public IssueVo changeStatus(Long issueId, Long currentUserId, String targetStatus) {
        IssueEntity issue = requireIssue(issueId);
        assertCanHandle(issue, currentUserId);

        IssueStatus from = IssueStatusMachine.parse(issue.getStatus());
        IssueStatus to = IssueStatusMachine.parse(targetStatus);
        // 非法流转直接拒绝；原地不动也拒绝，避免调用方以为"成功"了
        IssueStatusMachine.assertCanTransit(from, to);

        IssueEntity update = new IssueEntity();
        update.setId(issueId);
        update.setStatus(to.name());
        issueMapper.updateById(update);

        issue.setStatus(to.name());
        log.info("issue status changed, issueId={}, {} -> {}, operator={}", issueId, from, to, currentUserId);
        return toVo(issue);
    }

    @Override
    public PageResult<IssueVo> listByAsset(Long assetId, PageQuery query) {
        Page<IssueEntity> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        Page<IssueEntity> result = issueMapper.selectPage(page, new LambdaQueryWrapper<IssueEntity>()
                .eq(IssueEntity::getAssetId, assetId)
                .eq(IssueEntity::getDeleted, BaseEntity.NOT_DELETED)
                .orderByDesc(IssueEntity::getCreateTime));
        List<IssueEntity> records = result.getRecords();
        Map<Long, String> names = authorNameResolver.resolve(
                records.stream().map(IssueEntity::getAuthorId).toList());
        return PageResult.of(records.stream().map(e -> toVo(e, names)).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    private IssueEntity requireIssue(Long issueId) {
        IssueEntity issue = issueMapper.selectOne(new LambdaQueryWrapper<IssueEntity>()
                .eq(IssueEntity::getId, issueId)
                .eq(IssueEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (issue == null) {
            throw new BizException(ErrorCode.ISSUE_NOT_FOUND);
        }
        return issue;
    }

    private void assertCanHandle(IssueEntity issue, Long currentUserId) {
        if (currentUserId != null && currentUserId.equals(issue.getAuthorId())) {
            return;
        }
        if (Role.isPrivileged(UserContext.getRole())) {
            return;
        }
        throw new BizException(ErrorCode.FORBIDDEN, "only the author or a maintainer can change issue status");
    }

    private IssueVo toVo(IssueEntity entity) {
        return toVo(entity, authorNameResolver.resolve(List.of(entity.getAuthorId())));
    }

    private IssueVo toVo(IssueEntity entity, Map<Long, String> names) {
        return new IssueVo(entity.getId(), entity.getAuthorId(), entity.getAssetId(), entity.getVersion(),
                entity.getTitle(), entity.getContent(), entity.getStatus(), entity.getCreateTime(),
                names.get(entity.getAuthorId()));
    }
}
