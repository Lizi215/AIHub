package com.aihub.community.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aihub.api.dto.IssueIndexDoc;
import com.aihub.api.dto.PostIndexDoc;
import com.aihub.common.config.JacksonConfig;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.entity.IssueEntity;
import com.aihub.community.entity.PostEntity;
import com.aihub.community.mapper.IssueMapper;
import com.aihub.community.mapper.PostMapper;
import com.aihub.community.service.CommunityIndexSourceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 对账数据源实现。
 *
 * <p>帖子有事件、Issue 没有，所以这里 opVersion 恒为 1，对账只在「索引里没有」时写入；
 * 代价是 Issue 状态变更后索引会滞后到下次对账，等 Issue 事件补齐后可换成真实版本。
 */
@Service
public class CommunityIndexSourceServiceImpl implements CommunityIndexSourceService {

    /** 帖子 / Issue 的业务对象版本：没有事件，恒为 1 */
    private static final long STATIC_OP_VERSION = 1L;

    private final PostMapper postMapper;
    private final IssueMapper issueMapper;

    public CommunityIndexSourceServiceImpl(PostMapper postMapper, IssueMapper issueMapper) {
        this.postMapper = postMapper;
        this.issueMapper = issueMapper;
    }

    @Override
    public PageResult<PostIndexDoc> scanPosts(String updatedAfterIso, PageQuery query) {
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getDeleted, BaseEntity.NOT_DELETED);
        applyUpdatedAfter(wrapper, updatedAfterIso, PostEntity::getUpdateTime);
        wrapper.orderByAsc(PostEntity::getUpdateTime).orderByAsc(PostEntity::getId);

        Page<PostEntity> page = postMapper.selectPage(
                new Page<>(query.pageOrDefault(), query.sizeOrDefault()), wrapper);
        return PageResult.of(page.getRecords().stream()
                        .map(post -> new PostIndexDoc(
                                String.valueOf(post.getId()),
                                post.getType(),
                                post.getTitle(),
                                post.getContent(),
                                String.valueOf(post.getAuthorId()),
                                post.getAssetId() == null ? null : String.valueOf(post.getAssetId()),
                                STATIC_OP_VERSION,
                                format(post.getCreateTime()),
                                format(post.getUpdateTime())))
                        .toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public PageResult<String> scanRemovedPosts(String updatedAfterIso, PageQuery query) {
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<PostEntity>()
                .ne(PostEntity::getDeleted, BaseEntity.NOT_DELETED);
        applyUpdatedAfter(wrapper, updatedAfterIso, PostEntity::getUpdateTime);
        wrapper.orderByAsc(PostEntity::getUpdateTime).orderByAsc(PostEntity::getId);

        Page<PostEntity> page = postMapper.selectPage(
                new Page<>(query.pageOrDefault(), query.sizeOrDefault()), wrapper);
        return PageResult.of(page.getRecords().stream().map(p -> String.valueOf(p.getId())).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public PageResult<IssueIndexDoc> scanIssues(String updatedAfterIso, PageQuery query) {
        LambdaQueryWrapper<IssueEntity> wrapper = new LambdaQueryWrapper<IssueEntity>()
                .eq(IssueEntity::getDeleted, BaseEntity.NOT_DELETED);
        applyUpdatedAfter(wrapper, updatedAfterIso, IssueEntity::getUpdateTime);
        wrapper.orderByAsc(IssueEntity::getUpdateTime).orderByAsc(IssueEntity::getId);

        Page<IssueEntity> page = issueMapper.selectPage(
                new Page<>(query.pageOrDefault(), query.sizeOrDefault()), wrapper);
        return PageResult.of(page.getRecords().stream()
                        .map(issue -> new IssueIndexDoc(
                                String.valueOf(issue.getId()),
                                issue.getAssetId() == null ? null : String.valueOf(issue.getAssetId()),
                                issue.getVersion(),
                                issue.getTitle(),
                                issue.getContent(),
                                issue.getStatus(),
                                String.valueOf(issue.getAuthorId()),
                                STATIC_OP_VERSION,
                                format(issue.getCreateTime()),
                                format(issue.getUpdateTime())))
                        .toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public PageResult<String> scanRemovedIssues(String updatedAfterIso, PageQuery query) {
        LambdaQueryWrapper<IssueEntity> wrapper = new LambdaQueryWrapper<IssueEntity>()
                .ne(IssueEntity::getDeleted, BaseEntity.NOT_DELETED);
        applyUpdatedAfter(wrapper, updatedAfterIso, IssueEntity::getUpdateTime);
        wrapper.orderByAsc(IssueEntity::getUpdateTime).orderByAsc(IssueEntity::getId);

        Page<IssueEntity> page = issueMapper.selectPage(
                new Page<>(query.pageOrDefault(), query.sizeOrDefault()), wrapper);
        return PageResult.of(page.getRecords().stream().map(i -> String.valueOf(i.getId())).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    private <T> void applyUpdatedAfter(LambdaQueryWrapper<T> wrapper, String updatedAfterIso,
                                       com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column) {
        if (StrUtil.isBlank(updatedAfterIso)) {
            return;
        }
        try {
            wrapper.gt(column, LocalDateTime.parse(updatedAfterIso.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (DateTimeParseException e) {
            // 解析失败直接报错：静默退化成全量扫描会让对账越来越慢，且难以归因
            throw new BizException(ErrorCode.PARAM_INVALID, "invalid updatedAfter: " + updatedAfterIso);
        }
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ofPattern(JacksonConfig.DATE_TIME_PATTERN));
    }
}
