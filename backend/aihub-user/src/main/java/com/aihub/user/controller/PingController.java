package com.aihub.user.controller;

import com.aihub.api.asset.AssetPingApi;
import com.aihub.api.dto.PingResponse;
import com.aihub.common.config.JacksonConfig;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.Result;
import com.aihub.common.trace.TraceConstants;
import com.aihub.user.vo.PingVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 对外连通性探测，经 Gateway 转发后返回统一响应体。
 *
 * <p>同时通过 Feign 调用 Asset Service，验证服务发现与跨服务 traceId 透传。
 */
@RestController
@RequestMapping("/api/v1")
public class PingController {

    private static final Logger log = LoggerFactory.getLogger(PingController.class);

    private final AssetPingApi assetPingApi;
    private final String applicationName;
    private final String port;

    public PingController(AssetPingApi assetPingApi,
                          @Value("${spring.application.name}") String applicationName,
                          @Value("${server.port}") String port) {
        this.assetPingApi = assetPingApi;
        this.applicationName = applicationName;
        this.port = port;
    }

    @GetMapping("/ping")
    public Result<PingVo> ping() {
        Result<PingResponse> downstream = assetPingApi.ping();
        // 业务失败走 HTTP 200 + 非 0 code，必须显式判断，否则下游失败会被当成成功
        if (downstream.code() != ErrorCode.SUCCESS.getCode()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "downstream aihub-asset failed: " + downstream.message());
        }
        log.info("ping served by {}, downstream asset={}", applicationName, downstream.data());
        return Result.ok(new PingVo(
                applicationName,
                port,
                MDC.get(TraceConstants.MDC_TRACE_ID),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern(JacksonConfig.DATE_TIME_PATTERN)),
                downstream.data().service(),
                downstream.data().instance()));
    }
}
