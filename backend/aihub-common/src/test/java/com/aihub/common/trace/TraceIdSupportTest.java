package com.aihub.common.trace;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 生成与解析规则。上游已带 traceId 时必须沿用，否则链路会断裂。
 */
class TraceIdSupportTest {

    @Test
    void shouldKeepUpstreamTraceId() {
        assertThat(TraceIdSupport.resolveTraceId("abc-123")).isEqualTo("abc-123");
    }

    @Test
    void shouldTrimUpstreamTraceId() {
        assertThat(TraceIdSupport.resolveTraceId("  abc-123  ")).isEqualTo("abc-123");
    }

    @Test
    void shouldGenerateWhenHeaderMissingOrBlank() {
        assertThat(TraceIdSupport.resolveTraceId(null)).isNotBlank();
        assertThat(TraceIdSupport.resolveTraceId("")).isNotBlank();
        assertThat(TraceIdSupport.resolveTraceId("   ")).isNotBlank();
    }

    @Test
    void shouldGenerateUuidWithoutHyphen() {
        String traceId = TraceIdSupport.generateTraceId();

        assertThat(traceId).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void shouldGenerateDistinctTraceIds() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            generated.add(TraceIdSupport.generateTraceId());
        }

        assertThat(generated).hasSize(1000);
    }
}
