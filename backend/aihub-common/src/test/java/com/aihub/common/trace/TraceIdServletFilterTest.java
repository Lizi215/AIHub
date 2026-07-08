package com.aihub.common.trace;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Servlet 栈 traceId 过滤器：MDC 必须在链路内可见、链路结束后清理，且响应头回写。
 */
class TraceIdServletFilterTest {

    private final TraceIdServletFilter filter = new TraceIdServletFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldPropagateUpstreamTraceIdAndExposeItInMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceConstants.TRACE_ID_HEADER, "upstream-trace-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> traceIdInsideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> traceIdInsideChain.set(MDC.get(TraceConstants.MDC_TRACE_ID));

        filter.doFilter(request, response, chain);

        assertThat(traceIdInsideChain.get()).isEqualTo("upstream-trace-1");
        assertThat(response.getHeader(TraceConstants.TRACE_ID_HEADER)).isEqualTo("upstream-trace-1");
        assertThat(request.getAttribute(TraceConstants.ATTR_TRACE_ID)).isEqualTo("upstream-trace-1");
    }

    @Test
    void shouldGenerateTraceIdWhenUpstreamMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(TraceConstants.TRACE_ID_HEADER)).hasSize(32);
    }

    @Test
    void shouldClearMdcAfterRequestToAvoidThreadReuseLeak() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceConstants.TRACE_ID_HEADER, "leak-check");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(MDC.get(TraceConstants.MDC_TRACE_ID)).isNull();
    }

    @Test
    void shouldClearMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception ignored) {
            // 预期异常，重点验证 finally 中的清理是否执行
        }

        assertThat(MDC.get(TraceConstants.MDC_TRACE_ID)).isNull();
    }
}
