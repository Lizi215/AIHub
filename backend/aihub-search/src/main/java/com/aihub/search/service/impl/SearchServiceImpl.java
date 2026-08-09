package com.aihub.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.NamedValue;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.search.dto.SearchSort;
import com.aihub.search.index.SearchDocType;
import com.aihub.search.support.AuthorNameResolver;
import com.aihub.search.service.SearchService;
import com.aihub.search.vo.SearchHit;
import com.aihub.search.vo.SearchPageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 搜索实现。
 *
 * <p>查询一律打 `*_read` 别名，重建索引时切别名即可，代码零改动。
 * `from + size` 超过预留下限时主动拦截，返回明确错误码并提示改用 searchAfter，
 * 而不是把 ES 的 400 抛给前端。
 *
 * <p>中文检索用 ES 内置 `cjk` 分析器（bigram），IK 未安装时的近似方案，
 * 词边界不如 IK，相关性排序偏弱。装上 IK 后改 mapping 的 analyzer 并重建索引即可。
 */
@Service
public class SearchServiceImpl implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    /** 单页最大条数，展示型接口给太大只会拖慢响应 */
    private static final int MAX_PAGE_SIZE = 50;

    /** from + size 上限，ES 默认 max_result_window=10000，这里留安全余量 */
    private static final int MAX_RESULT_WINDOW = 9_000;

    private static final int HIGHLIGHT_FRAGMENT_SIZE = 100;

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;
    private final AuthorNameResolver authorNameResolver;

    public SearchServiceImpl(ElasticsearchClient client, ObjectMapper objectMapper,
                             AuthorNameResolver authorNameResolver) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.authorNameResolver = authorNameResolver;
    }

    @Override
    public SearchPageResult<SearchHit> searchAssets(String keyword, String category, String tag, String sort,
                                                   Integer page, Integer size, String searchAfter, Long ownerId) {
        List<Term> filters = new ArrayList<>(3);
        if (category != null && !category.isBlank()) {
            filters.add(new Term("category", category));
        }
        if (tag != null && !tag.isBlank()) {
            filters.add(new Term("tags", tag));
        }
        // 作者过滤：authorId 是 keyword 字段，term 精确匹配
        if (ownerId != null) {
            filters.add(new Term("authorId", String.valueOf(ownerId)));
        }
        SearchPageResult<SearchHit> result = search(SearchDocType.ASSET, keyword, filters,
                sort, page, size, searchAfter,
                List.of("title^3", "description", "readme"));
        // 昵称在查询响应组装时解析，不动 ES 映射、无需重建索引
        attachAuthorNames(result);
        return result;
    }

    /** 给命中的资源文档补 authorName（source 里已有 authorId）。 */
    private void attachAuthorNames(SearchPageResult<SearchHit> result) {
        if (result == null || result.records() == null || result.records().isEmpty()) {
            return;
        }
        List<Long> authorIds = result.records().stream()
                .map(hit -> parseLong(hit.source() == null ? null : hit.source().get("authorId")))
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, String> names = authorNameResolver.resolve(authorIds);
        if (names.isEmpty()) {
            return;
        }
        for (SearchHit hit : result.records()) {
            if (hit.source() == null) {
                continue;
            }
            Long authorId = parseLong(hit.source().get("authorId"));
            String name = authorId == null ? null : names.get(authorId);
            if (name != null) {
                hit.source().put("authorName", name);
            }
        }
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public SearchPageResult<SearchHit> searchPosts(String keyword, String type, String sort,
                                                  Integer page, Integer size, String searchAfter) {
        return search(SearchDocType.POST, keyword,
                type == null || type.isBlank() ? List.of() : List.of(new Term("type", type)),
                sort, page, size, searchAfter,
                List.of("title^3", "content"));
    }

    @Override
    public SearchPageResult<SearchHit> searchIssues(String keyword, String status, String sort,
                                                   Integer page, Integer size, String searchAfter) {
        return search(SearchDocType.ISSUE, keyword,
                status == null || status.isBlank() ? List.of() : List.of(new Term("status", status)),
                sort, page, size, searchAfter,
                List.of("title^3", "content"));
    }

    /* ------------------------------------------------------------------ 内部实现 */

    private SearchPageResult<SearchHit> search(SearchDocType docType, String keyword, List<Term> filters,
                                               String sort, Integer page, Integer size, String searchAfter,
                                               List<String> searchFields) {
        SearchSort sortSpec = SearchSort.parse(sort);
        int pageSize = normalizeSize(size);
        int pageNumber = page == null || page < 1 ? 1 : page;
        int from = (pageNumber - 1) * pageSize;

        List<FieldValue> after = null;
        if (searchAfter != null && !searchAfter.isBlank()) {
            // 用游标时不能再传 from，两者语义冲突，ES 会报错
            after = sortSpec.decodeToken(searchAfter.trim(), objectMapper);
        } else if (from + pageSize > MAX_RESULT_WINDOW) {
            // 主动拦截并提示改用 searchAfter，而不是让调用方看到 ES 的 400
            throw new BizException(ErrorCode.SEARCH_QUERY_INVALID,
                    "from + size exceeds " + MAX_RESULT_WINDOW + ", please use searchAfter for deep paging");
        }

        Query query = buildQuery(keyword, filters, searchFields);
        // highlight 用 <em> 包裹命中词，前端负责转义渲染。
        // 字段名要去掉 multi_match 的权重后缀（title^3 → title），否则 ES 报未知字段
        Highlight highlight = Highlight.of(h -> h
                .fields(searchFields.stream()
                        .map(field -> field.split("\\^")[0])
                        .distinct()
                        .map(field -> NamedValue.of(field, HighlightField.of(hf -> hf)))
                        .toList())
                .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE));

        int requestSize = pageSize;
        List<FieldValue> searchAfterValues = after;
        try {
            SearchResponse<Map> response = client.search(request -> {
                request.index(docType.readAlias())
                        .query(query)
                        .highlight(highlight)
                        .sort(sortSpec.clauses())
                        .size(requestSize)
                        .trackTotalHits(t -> t.enabled(true));
                if (searchAfterValues == null) {
                    request.from(from);
                } else {
                    request.searchAfter(searchAfterValues);
                }
                return request;
            }, Map.class);
            return toResult(response, docType, sortSpec, pageNumber, pageSize, after != null);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SEARCH_ENGINE_UNAVAILABLE,
                    "search request failed: " + e.getMessage());
        }
    }

    private SearchPageResult<SearchHit> toResult(SearchResponse<Map> response, SearchDocType docType,
                                                 SearchSort sortSpec, int page, int size,
                                                 boolean usingCursor) {
        List<Hit<Map>> hits = response.hits().hits();
        List<SearchHit> records = new ArrayList<>(hits.size());
        String nextCursor = null;
        for (Hit<Map> hit : hits) {
            @SuppressWarnings("unchecked")
            Map<String, Object> source = hit.source() == null ? Map.of() : (Map<String, Object>) hit.source();
            records.add(new SearchHit(hit.id(), hit.score(), source, hit.highlight()));
            // 游标取本页最后一条，下一页从这里之后继续
            nextCursor = sortSpec.encodeToken(hit.score(), source, hit.id(), objectMapper);
        }
        long total = response.hits().total() == null ? records.size() : response.hits().total().value();
        // from/size 翻页时看 from + size 是否小于总数；
        // search_after 时 page 参数无意义，只能按本页是否取满判断
        boolean hasNext = usingCursor
                ? records.size() == size
                : (long) page * size < total;
        log.debug("search done, index={}, hits={}, total={}, hasNext={}",
                docType.readAlias(), records.size(), total, hasNext);
        return new SearchPageResult<>(records, total, page, size, hasNext ? nextCursor : null);
    }

    /**
     * 组装查询：关键字走 multi_match（字段带权重），筛选走 filter。
     *
     * <p>筛选用 `filter` 而非 `must`：filter 不参与打分且可利用缓存。
     */
    private Query buildQuery(String keyword, List<Term> filters, List<String> searchFields) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        if (keyword == null || keyword.isBlank()) {
            bool.must(Query.of(q -> q.matchAll(m -> m)));
        } else {
            String trimmed = keyword.trim();
            bool.must(Query.of(q -> q.multiMatch(mm -> mm
                    .query(trimmed)
                    .fields(searchFields)
                    .type(TextQueryType.BestFields))));
        }
        for (Term filter : filters) {
            bool.filter(Query.of(q -> q.term(t -> t.field(filter.field()).value(filter.value()))));
        }
        return Query.of(q -> q.bool(bool.build()));
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return 10;
        }
        if (size > MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.SEARCH_QUERY_INVALID,
                    "size must be less than or equal to " + MAX_PAGE_SIZE);
        }
        return size;
    }

    /** 等值筛选条件 */
    private record Term(String field, String value) {
    }
}
