package com.aihub.community.service;

import com.aihub.api.dto.IssueQueryItem;
import com.aihub.api.dto.PostQueryItem;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;

/**
 * 面向 Agent 的内部只读查询（实现 {@link com.aihub.api.community.CommunityQueryApi}）。
 *
 * <p>不做关键字全文检索：社区全文检索依赖 ES，这里只按资源 / 类型筛选，
 * 避免用 MySQL {@code LIKE} 冒充搜索给出不完整的结论。
 */
public interface CommunityQueryService {

    /**
     * 按资源（可选状态）查询 Issue。
     *
     * @param status OPEN / PROCESSING / CLOSED，可为空表示不限；非法取值直接拒绝
     */
    PageResult<IssueQueryItem> listIssues(Long assetId, String status, PageQuery query);

    /**
     * 按类型查询帖子。
     *
     * @param type EXPERIENCE / GUIDE / QUESTION，可为空表示不限；非法取值直接拒绝
     */
    PageResult<PostQueryItem> listPosts(String type, PageQuery query);
}
