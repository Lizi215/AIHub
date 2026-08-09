package com.aihub.search.controller;

import com.aihub.api.dto.AssetQueryItem;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.search.service.SearchService;
import com.aihub.search.vo.SearchHit;
import com.aihub.search.vo.SearchPageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Search Service 的内部查询端点，供 AI Service 经 {@code SearchQueryApi} 调用。
 *
 * <p>Agent 的 {@code search_assets} 工具原来走 Asset Service 的 MySQL LIKE，只能匹配名称与简介，
 * 搜不到 README 也没有相关度排序，检索改由本服务承担。
 *
 * <p>路径挂在 {@code /internal/v1} 且返回类型化 DTO，而不是直接暴露公开的
 * {@code /api/v1/search/**}：后者返回高亮片段与 search_after 游标，字段按 ES 文档原样暴露；
 * 服务间调用需要稳定契约，不该让调用方感知 ES 内部结构。
 *
 * <p>与公开搜索的差异：不带 {@code sort} / {@code searchAfter}，Agent 每次都是新检索、
 * 不拿游标翻页；也不返回高亮，模型不需要 {@code <em>} 标记。
 */
@RestController
@RequestMapping("/internal/v1/query")
public class InternalSearchQueryController {

    private final SearchService searchService;

    public InternalSearchQueryController(SearchService searchService) {
        this.searchService = searchService;
    }

    /** 全文检索已发布资源，草稿/已下架不进索引自然不会命中。 */
    @GetMapping("/assets")
    public Result<PageResult<AssetQueryItem>> searchAssets(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        SearchPageResult<SearchHit> hits = searchService.searchAssets(keyword, category, tag,
                null, page, size, null, null);
        return Result.ok(PageResult.of(toItems(hits), hits.total(), hits.page(), hits.size()));
    }

    /** ES 文档 → 服务间 DTO。字段映射集中在这里，ES 结构变化不外溢到调用方。 */
    private List<AssetQueryItem> toItems(SearchPageResult<SearchHit> hits) {
        List<AssetQueryItem> items = new ArrayList<>(hits.records().size());
        for (SearchHit hit : hits.records()) {
            Map<String, Object> source = hit.source();
            Long id = asLong(source.get("id"));
            if (id == null) {
                // 索引里没有 id 是坏数据，宁可不返回也不能给调用方一个无法回查的候选
                continue;
            }
            items.add(new AssetQueryItem(
                    id,
                    asLong(source.get("authorId")),
                    asString(source.get("title")),
                    asString(source.get("description")),
                    asString(source.get("category")),
                    asString(source.get("status")),
                    asString(source.get("latestVersion"))));
        }
        return items;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
