package com.aihub.community.service;

import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreateCommentRequest;
import com.aihub.community.vo.PostCommentVo;

/** 帖子评论。 */
public interface PostCommentService {

    /** 评论，返回评论 id。帖子不存在时拒绝。 */
    Long create(Long postId, Long authorId, CreateCommentRequest request);

    /** 按帖子分页列出评论（按时间升序，符合阅读顺序）。 */
    PageResult<PostCommentVo> listByPost(Long postId, PageQuery query);
}
