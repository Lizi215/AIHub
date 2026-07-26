package com.aihub.asset.config;

import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.Result;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Sentinel 拦截后的统一响应。
 *
 * <p>默认实现返回纯文本 "Blocked by Sentinel"，前端拿到的不是统一响应体；
 * 这里换成 HTTP 429 + 统一 Result（错误码 {@link ErrorCode#RATE_LIMITED}）。
 *
 * <p>限流不用业务错误码：它是「暂时性、可重试」的基础设施信号，HTTP 429 本身就是标准语义。
 */
@Component
public class SentinelBlockExceptionHandler implements BlockExceptionHandler {

    private final ObjectMapper objectMapper;

    public SentinelBlockExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       String resourceName, BlockException e) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ErrorCode.RATE_LIMITED)));
    }
}
