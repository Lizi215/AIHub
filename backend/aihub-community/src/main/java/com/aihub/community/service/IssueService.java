package com.aihub.community.service;

import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreateIssueRequest;
import com.aihub.community.vo.IssueVo;

/**
 * Issue 追踪。
 */
public interface IssueService {

    /** 提出 Issue，初始状态 OPEN。 */
    Long create(Long authorId, CreateIssueRequest request);

    IssueVo detail(Long issueId);

    /**
     * 变更 Issue 状态，流转规则由 {@link com.aihub.community.domain.IssueStatusMachine} 判定。
     *
     * <p>权限：作者可处理自己提的 Issue（走归属）；Maintainer / Admin 可处理任意 Issue（走角色）。
     */
    IssueVo changeStatus(Long issueId, Long currentUserId, String targetStatus);

    /** 按资源分页列出 Issue。 */
    PageResult<IssueVo> listByAsset(Long assetId, PageQuery query);
}
