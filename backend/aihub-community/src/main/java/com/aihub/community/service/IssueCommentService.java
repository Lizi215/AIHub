package com.aihub.community.service;

import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreateCommentRequest;
import com.aihub.community.vo.IssueCommentVo;

/** Issue 评论：资源的讨论回复。 */
public interface IssueCommentService {

    /** 发表评论，返回评论 id。 */
    Long create(Long issueId, Long authorId, CreateCommentRequest request);

    /** 按 Issue 分页列出评论（时间升序）。 */
    PageResult<IssueCommentVo> listByIssue(Long issueId, PageQuery query);
}
