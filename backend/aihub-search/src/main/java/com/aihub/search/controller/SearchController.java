package com.aihub.search.controller;

import com.aihub.common.result.Result;
import com.aihub.search.service.SearchService;
import com.aihub.search.vo.SearchHit;
import com.aihub.search.vo.SearchPageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索接口。
 *
 * <pre>
 * GET /api/v1/search/assets?keyword=&category=&sort=&page=&size=&searchAfter=
 * GET /api/v1/search/posts ?keyword=&type=&sort=&page=&size=&searchAfter=
 * GET /api/v1/search/issues?keyword=&status=&sort=&page=&size=&searchAfter=
 * </pre>
 *
 * <p>按 doc_type 分三个接口而不是一个大而全的 {@code /search?type=}：三种文档的字段与
 * 筛选维度不同（资源按分类、帖子按类型、Issue 按状态），合成一个只会让参数互相污染。
 *
 * <p>搜索是公开读，Visitor 也能搜，Gateway 的公开规则表已放行 GET /api/v1/search/**。
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/assets")
    public Result<SearchPageResult<SearchHit>> searchAssets(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String searchAfter,
            @RequestParam(required = false) Long ownerId) {
        return Result.ok(searchService.searchAssets(keyword, category, tag, sort, page, size, searchAfter, ownerId));
    }

    @GetMapping("/posts")
    public Result<SearchPageResult<SearchHit>> searchPosts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String searchAfter) {
        return Result.ok(searchService.searchPosts(keyword, type, sort, page, size, searchAfter));
    }

    @GetMapping("/issues")
    public Result<SearchPageResult<SearchHit>> searchIssues(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String searchAfter) {
        return Result.ok(searchService.searchIssues(keyword, status, sort, page, size, searchAfter));
    }
}
