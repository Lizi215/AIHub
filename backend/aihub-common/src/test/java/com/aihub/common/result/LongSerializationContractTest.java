package com.aihub.common.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aihub.common.config.JacksonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定「哪些 long 变字符串、哪些保持数字」这条契约。
 *
 * <p>全局 Long→String 是为了 19 位雪花 ID 不超出 JS 安全整数范围，但分页计数、字节数、
 * 时长不是 ID。规则保持默认安全，只对能确定不是 ID 的字段显式开例外。
 */
class LongSerializationContractTest {

    private final ObjectMapper objectMapper;

    LongSerializationContractTest() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().aihubJacksonCustomizer().customize(builder);
        this.objectMapper = builder.build();
    }

    @Test
    void snowflakeIdMustStayString() throws Exception {
        String json = objectMapper.writeValueAsString(new Holder(1945678901234567890L));

        assertThat(json).isEqualTo("{\"id\":\"1945678901234567890\"}");
    }

    @Test
    void paginationCountsMustBeNumbers() throws Exception {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 1945678901234L, 2L, 20L);

        String json = objectMapper.writeValueAsString(page);

        // 计数必须是数字：el-pagination 等组件要求 total 为 Number
        assertThat(json).contains("\"total\":1945678901234");
        assertThat(json).contains("\"page\":2");
        assertThat(json).contains("\"size\":20");
        assertThat(json).doesNotContain("\"total\":\"");
        assertThat(json).doesNotContain("\"page\":\"");
        assertThat(json).doesNotContain("\"size\":\"");
    }

    @Test
    void idInsidePageRecordsMustStillBeStringWhileCountsAreNumbers() throws Exception {
        PageResult<Holder> page = PageResult.of(List.of(new Holder(1945678901234567890L)), 1L, 1L, 10L);

        String json = objectMapper.writeValueAsString(page);

        assertThat(json).contains("\"id\":\"1945678901234567890\"");
        assertThat(json).contains("\"total\":1");
        assertThat(json).contains("\"page\":1");
    }

    /** 模拟一个普通业务 VO：这里的 Long 是雪花 ID */
    record Holder(Long id) {
    }
}
