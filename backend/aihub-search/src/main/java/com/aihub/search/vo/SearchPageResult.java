package com.aihub.search.vo;

import com.aihub.common.jackson.NumericLongSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.List;

/**
 * 搜索结果分页。
 *
 * <p>不直接用 {@code PageResult}：搜索需要额外带出深分页游标 {@code nextSearchAfter}，
 * 而 PageResult 是通用列表契约，不该为搜索特例扩字段。
 *
 * <p>{@code total} 是 ES 报告的命中总数，用 search_after 连续翻页时是满足条件的总数，不是剩余数量。
 *
 * @param nextSearchAfter 下一页游标；为空表示没有下一页
 */
public record SearchPageResult<T>(
        List<T> records,
        @JsonSerialize(using = NumericLongSerializer.class) long total,
        @JsonSerialize(using = NumericLongSerializer.class) long page,
        @JsonSerialize(using = NumericLongSerializer.class) long size,
        String nextSearchAfter) {
}
