package com.aihub.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson 全局配置契约（docs/API.md §5）：Long → String、时间 ISO-8601、空值策略统一。
 */
class JacksonConfigTest {

    private final ObjectMapper objectMapper;

    JacksonConfigTest() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().aihubJacksonCustomizer().customize(builder);
        this.objectMapper = builder.build();
    }

    @Test
    void shouldSerializeLongAsStringToPreserveSnowflakeIdPrecision() throws Exception {
        SnowflakeHolder holder = new SnowflakeHolder(1945678901234567890L);

        String json = objectMapper.writeValueAsString(holder);

        // 必须以字符串出现，否则前端 JS 解析会丢精度
        assertThat(json).contains("\"id\":\"1945678901234567890\"");
    }

    @Test
    void shouldSerializePrimitiveLongAsString() throws Exception {
        String json = objectMapper.writeValueAsString(new PrimitiveLongHolder(1234567890123456789L));

        assertThat(json).contains("\"id\":\"1234567890123456789\"");
    }

    @Test
    void shouldSerializeLocalDateTimeAsIso8601WithoutTimestamp() throws Exception {
        String json = objectMapper.writeValueAsString(new TimeHolder(LocalDateTime.of(2026, 9, 23, 10, 30, 45)));

        assertThat(json).contains("\"createdAt\":\"2026-09-23T10:30:45\"");
        // 禁止时间戳
        assertThat(json).doesNotContain("1758");
    }

    @Test
    void shouldOmitNullFieldsGlobally() throws Exception {
        String json = objectMapper.writeValueAsString(new NullableHolder("present", null));

        assertThat(json).contains("\"name\":\"present\"");
        assertThat(json).doesNotContain("description");
    }

    record SnowflakeHolder(Long id) {
    }

    record PrimitiveLongHolder(long id) {
    }

    record TimeHolder(LocalDateTime createdAt) {
    }

    record NullableHolder(String name, String description) {
    }
}
