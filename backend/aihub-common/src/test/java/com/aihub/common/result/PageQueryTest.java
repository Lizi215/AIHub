package com.aihub.common.result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页请求契约（docs/API.md §4）：page 从 1 开始，未传时取默认值。
 */
class PageQueryTest {

    @Test
    void shouldFallBackToDefaultsWhenParamsMissing() {
        PageQuery query = new PageQuery(null, null);

        assertThat(query.pageOrDefault()).isEqualTo(1);
        assertThat(query.sizeOrDefault()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    void shouldKeepProvidedValues() {
        PageQuery query = new PageQuery(3, 50);

        assertThat(query.pageOrDefault()).isEqualTo(3);
        assertThat(query.sizeOrDefault()).isEqualTo(50);
    }

    @Test
    void pageShouldStartFromOneNotZero() {
        PageQuery query = new PageQuery(1, 10);

        assertThat(query.pageOrDefault()).isEqualTo(1);
    }
}
