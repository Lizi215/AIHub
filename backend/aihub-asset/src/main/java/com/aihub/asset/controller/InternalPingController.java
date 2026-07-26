package com.aihub.asset.controller;

import com.aihub.api.asset.AssetPingApi;
import com.aihub.api.dto.PingResponse;
import com.aihub.common.config.JacksonConfig;
import com.aihub.common.result.Result;
import com.aihub.common.trace.TraceConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 服务间探测接口，实现 {@link AssetPingApi} 契约。路径不暴露到公网。
 */
@RestController
public class InternalPingController implements AssetPingApi {

    private static final Logger log = LoggerFactory.getLogger(InternalPingController.class);

    private final String applicationName;
    private final String port;

    public InternalPingController(@Value("${spring.application.name}") String applicationName,
                                  @Value("${server.port}") String port) {
        this.applicationName = applicationName;
        this.port = port;
    }

    @Override
    public Result<PingResponse> ping() {
        String traceId = MDC.get(TraceConstants.MDC_TRACE_ID);
        log.info("internal ping served by {}, received traceId={}", applicationName, traceId);
        return Result.ok(new PingResponse(
                applicationName,
                port,
                traceId,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern(JacksonConfig.DATE_TIME_PATTERN))));
    }
}
