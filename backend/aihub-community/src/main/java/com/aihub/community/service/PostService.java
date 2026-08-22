package com.aihub.community.service;

import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.community.dto.CreatePostRequest;
import com.aihub.community.vo.PostVo;

/**
 * 社区帖子。
 */
public interface PostService {

    /** 发帖，返回帖子 id。 */
    Long create(Long authorId, CreatePostRequest request);

    /**
     * 详情。
     *
     * @throws com.aihub.common.exception.BizException 帖子不存在时 30001
     */
    PostVo detail(Long postId);

    /** 按类型分页列表；type 为空时返回全部类型。 */
    PageResult<PostVo> list(String type, PageQuery query);
}
