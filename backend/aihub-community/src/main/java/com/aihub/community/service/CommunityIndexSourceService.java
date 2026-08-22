package com.aihub.community.service;

import com.aihub.api.dto.IssueIndexDoc;
import com.aihub.api.dto.PostIndexDoc;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;

/**
 * 给 Search Service 对账任务提供增量数据源的内部只读接口。
 *
 * <p>与 {@code CommunityQueryService}（供 Agent 交互式查询）分开：对账按 updated_at 增量扫描，
 * 过滤维度不同，混在一起会互相牵制。
 */
public interface CommunityIndexSourceService {

    /** 增量扫描应进索引的帖子（按更新时间升序）。 */
    PageResult<PostIndexDoc> scanPosts(String updatedAfterIso, PageQuery query);

    /** 增量扫描应从索引移除的帖子 id（软删除的）。 */
    PageResult<String> scanRemovedPosts(String updatedAfterIso, PageQuery query);

    /** 增量扫描应进索引的 Issue。 */
    PageResult<IssueIndexDoc> scanIssues(String updatedAfterIso, PageQuery query);

    /** 增量扫描应从索引移除的 Issue id（软删除的）。 */
    PageResult<String> scanRemovedIssues(String updatedAfterIso, PageQuery query);
}
