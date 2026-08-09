package com.aihub.search.dto;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 排序模式与深分页游标。
 *
 * <p>深分页必须用 {@code search_after}：ES 的 {@code from + size} 超过
 * {@code index.max_result_window}（默认 10000）会直接报错，且深分页让协调节点付出 O(from) 代价。
 * {@code search_after} 用上一页最后一条的排序值继续取，代价是 O(size)，但要求排序键稳定唯一，
 * 因此每种排序都用 id 兜底。
 *
 * <p>游标格式 Base64(JSON 数组)，元素类型由排序模式决定。不用 ES 原始 sort 值直接透传，
 * 免得把内部实现细节暴露成客户端可构造的游标。
 */
public enum SearchSort {

    /** 相关度（默认）：_score desc, id asc */
    RELEVANCE,
    /** 最新：createdAt desc, id asc */
    LATEST,
    /**
     * 最热：downloadCount desc, id asc。
     *
     * <p>{@code downloadCount} 目前恒为 0（天级回写未做），该模式实际退化为按 id 排序，
     * 只保证是合法排序，不保证真的按热度。
     */
    HOTTEST;

    private static final String ID_FIELD = "id";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 解析排序参数；为空时取 RELEVANCE，非法值明确报错而不回退默认。 */
    public static SearchSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return RELEVANCE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.SEARCH_QUERY_INVALID,
                    "unknown sort: " + raw + " (expected relevance | latest | hottest)");
        }
    }

    /** 生成 ES 排序子句。 */
    public List<SortOptions> clauses() {
        List<SortOptions> options = new ArrayList<>(2);
        switch (this) {
            case RELEVANCE -> options.add(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
            case LATEST -> options.add(fieldSort("createdAt", SortOrder.Desc));
            case HOTTEST -> options.add(fieldSort("downloadCount", SortOrder.Desc));
        }
        // id 兜底：排序键不唯一时 search_after 会漏记录或重复
        options.add(fieldSort(ID_FIELD, SortOrder.Asc));
        return options;
    }

    private SortOptions fieldSort(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }

    /** 由当前命中的文档生成下一页游标。 */
    public String encodeToken(Double score, Map<String, Object> source, String id, ObjectMapper objectMapper) {
        List<Object> values = new ArrayList<>(2);
        switch (this) {
            case RELEVANCE -> values.add(score == null ? 0.0d : score);
            case LATEST -> values.add(epochMillis(stringValue(source, "createdAt")));
            case HOTTEST -> values.add(longValue(source, "downloadCount"));
        }
        values.add(id);
        try {
            String json = objectMapper.writeValueAsString(values);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BizException(ErrorCode.SEARCH_QUERY_INVALID, "failed to encode search cursor");
        }
    }

    /** 解析游标为 ES 的 search_after 值。 */
    public List<FieldValue> decodeToken(String token, ObjectMapper objectMapper) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            List<?> values = objectMapper.readValue(decoded, List.class);
            if (values.size() != 2) {
                throw new IllegalArgumentException("cursor must contain 2 values");
            }
            List<FieldValue> fieldValues = new ArrayList<>(2);
            fieldValues.add(toFieldValue(values.get(0)));
            fieldValues.add(FieldValue.of(String.valueOf(values.get(1))));
            return fieldValues;
        } catch (Exception e) {
            throw new BizException(ErrorCode.SEARCH_QUERY_INVALID, "invalid searchAfter cursor");
        }
    }

    private FieldValue toFieldValue(Object value) {
        if (value instanceof Number number) {
            return this == RELEVANCE
                    ? FieldValue.of(number.doubleValue())
                    : FieldValue.of(number.longValue());
        }
        return FieldValue.of(String.valueOf(value));
    }

    private Object epochMillis(String isoTime) {
        if (isoTime == null) {
            return 0L;
        }
        // date 类型的 search_after 用 epoch millis 最稳，不受时区格式影响
        return LocalDateTime.parse(isoTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    private String stringValue(Map<String, Object> source, String field) {
        Object value = source == null ? null : source.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private long longValue(Map<String, Object> source, String field) {
        Object value = source == null ? null : source.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    static String format(LocalDateTime time) {
        return time == null ? null : time.format(ISO);
    }
}
