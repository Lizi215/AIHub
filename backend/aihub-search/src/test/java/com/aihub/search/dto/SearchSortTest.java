package com.aihub.search.dto;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 排序与深分页游标。
 *
 * <p>游标编解码不对称时表现为翻页丢记录或重复，而不是报错，因此要把往返一致性钉死。
 */
class SearchSortTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("排序解析：为空取默认 relevance，非法值明确报错而不是静默回退")
    void shouldParseSortSafely() {
        assertThat(SearchSort.parse(null)).isEqualTo(SearchSort.RELEVANCE);
        assertThat(SearchSort.parse("  ")).isEqualTo(SearchSort.RELEVANCE);
        assertThat(SearchSort.parse("LATEST")).isEqualTo(SearchSort.LATEST);

        assertThatThrownBy(() -> SearchSort.parse("byMagic"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SEARCH_QUERY_INVALID);
    }

    @Test
    @DisplayName("排序子句：主排序之外必须带 id 兜底，否则深分页会漏记录")
    void shouldAlwaysAppendIdTiebreaker() {
        for (SearchSort sort : SearchSort.values()) {
            assertThat(sort.clauses()).as("%s clauses", sort).hasSize(2);
        }
    }

    @Test
    @DisplayName("深分页游标：编码后能解回同样数量的排序值（relevance）")
    void shouldRoundTripCursorForRelevance() {
        Map<String, Object> source = Map.of("id", "100", "createdAt", "2026-09-24T13:00:00");

        String token = SearchSort.RELEVANCE.encodeToken(1.5d, source, "100", objectMapper);
        List<FieldValue> values = SearchSort.RELEVANCE.decodeToken(token, objectMapper);

        assertThat(values).hasSize(2);
        assertThat(values.get(0).doubleValue()).isEqualTo(1.5d);
        assertThat(values.get(1).stringValue()).isEqualTo("100");
    }

    @Test
    @DisplayName("深分页游标：latest 用 createdAt 的 epoch millis，避免时区/格式歧义")
    void shouldEncodeLatestCursorAsEpochMillis() {
        Map<String, Object> source = Map.of("createdAt", "2026-09-24T13:00:00");

        String token = SearchSort.LATEST.encodeToken(null, source, "100", objectMapper);
        List<FieldValue> values = SearchSort.LATEST.decodeToken(token, objectMapper);

        assertThat(values.get(0).longValue()).isPositive();
        assertThat(values.get(1).stringValue()).isEqualTo("100");
    }

    @Test
    @DisplayName("非法游标被拒绝，而不是当成空游标从头开始")
    void shouldRejectInvalidCursor() {
        assertThatThrownBy(() -> SearchSort.RELEVANCE.decodeToken("not-a-cursor!!", objectMapper))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SEARCH_QUERY_INVALID);
    }
}
