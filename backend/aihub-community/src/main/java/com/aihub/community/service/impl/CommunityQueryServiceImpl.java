package com.aihub.community.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aihub.api.dto.IssueQueryItem;
import com.aihub.api.dto.PostQueryItem;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.domain.IssueStatusMachine;
import com.aihub.community.entity.IssueEntity;
import com.aihub.community.entity.PostEntity;
import com.aihub.community.enums.IssueStatus;
import com.aihub.community.enums.PostType;
import com.aihub.community.mapper.IssueMapper;
import com.aihub.community.mapper.PostMapper;
import com.aihub.community.service.CommunityQueryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

/**
 * Agent 内部只读查询实现。
 *
 * <p>参数由模型生成不可信任，服务侧再校验一次是纵深防御。
 */
@Service
public class CommunityQueryServiceImpl implements CommunityQueryService {

    private final IssueMapper issueMapper;
    private final PostMapper postMapper;

    public CommunityQueryServiceImpl(IssueMapper issueMapper, PostMapper postMapper) {
        this.issueMapper = issueMapper;
        this.postMapper = postMapper;
    }

    @Override
    public PageResult<IssueQueryItem> listIssues(Long assetId, String status, PageQuery query) {
        if (assetId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "assetId is required when querying issues");
        }
        LambdaQueryWrapper<IssueEntity> wrapper = new LambdaQueryWrapper<IssueEntity>()
                .eq(IssueEntity::getAssetId, assetId)
                .eq(IssueEntity::getDeleted, BaseEntity.NOT_DELETED);
        if (StrUtil.isNotBlank(status)) {
            // 复用状态机里的解析：非法状态明确报错，而不是当成没传而返回全部
            IssueStatus parsed = IssueStatusMachine.parse(status);
            wrapper.eq(IssueEntity::getStatus, parsed.name());
        }
        wrapper.orderByDesc(IssueEntity::getCreateTime);

        Page<IssueEntity> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        Page<IssueEntity> result = issueMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords().stream().map(this::toItem).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public PageResult<PostQueryItem> listPosts(String type, PageQuery query) {
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getDeleted, BaseEntity.NOT_DELETED);
        if (StrUtil.isNotBlank(type)) {
            wrapper.eq(PostEntity::getType, PostType.parse(type).name());
        }
        wrapper.orderByDesc(PostEntity::getCreateTime);

        Page<PostEntity> page = new Page<>(query.pageOrDefault(), query.sizeOrDefault());
        Page<PostEntity> result = postMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords().stream().map(this::toItem).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    private IssueQueryItem toItem(IssueEntity issue) {
        return new IssueQueryItem(issue.getId(), issue.getAssetId(), issue.getVersion(),
                issue.getTitle(), issue.getContent(), issue.getStatus(), issue.getCreateTime());
    }

    private PostQueryItem toItem(PostEntity post) {
        return new PostQueryItem(post.getId(), post.getAuthorId(), post.getType(), post.getTitle(),
                post.getContent(), post.getAssetId(), post.getLikeCount(), post.getCreateTime());
    }
}
