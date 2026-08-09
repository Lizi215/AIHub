package com.aihub.search.service;

import com.aihub.search.vo.SearchHit;
import com.aihub.search.vo.SearchPageResult;

/**
 * 搜索能力，Elasticsearch 的唯一出口在这层之下。
 */
public interface SearchService {

    /**
     * 搜索资源。
     *
     * @param keyword     关键字，可空（空表示只按筛选条件列出）
     * @param category    分类筛选，可空
     * @param tag         标签筛选，可空（精确匹配 tags keyword 字段）
     * @param sort        排序：relevance / latest / hottest
     * @param searchAfter 深分页游标（上一页返回的 nextSearchAfter）
     */
    SearchPageResult<SearchHit> searchAssets(String keyword, String category, String tag, String sort,
                                             Integer page, Integer size, String searchAfter, Long ownerId);

    /** 搜索社区帖子，可按类型（EXPERIENCE / GUIDE / QUESTION）筛选。 */
    SearchPageResult<SearchHit> searchPosts(String keyword, String type, String sort,
                                            Integer page, Integer size, String searchAfter);

    /** 搜索 Issue，可按状态（OPEN / PROCESSING / CLOSED）筛选。 */
    SearchPageResult<SearchHit> searchIssues(String keyword, String status, String sort,
                                             Integer page, Integer size, String searchAfter);
}
