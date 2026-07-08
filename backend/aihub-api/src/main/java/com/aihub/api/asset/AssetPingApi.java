package com.aihub.api.asset;

import com.aihub.api.dto.PingResponse;
import com.aihub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Asset Service 的内部接口契约。
 *
 * <p>内部接口统一挂在 {@code /internal/v1} 下，Gateway 不为其配置路由，
 * 因此不会暴露到公网（docs/SECURITY.md §3）。
 *
 * <p>完整路径必须写进 {@code @GetMapping}，不能只依赖 {@code @FeignClient(path = ...)}：
 * 后者只影响客户端 URL 构造，不参与服务端映射，会导致 404。
 */
@FeignClient(name = "aihub-asset", contextId = "assetPingApi")
public interface AssetPingApi {

    /**
     * 连通性探测，用于验证 Nacos 服务发现与 Feign 调用链路。
     *
     * @return 提供方服务信息与 traceId
     */
    @GetMapping("/internal/v1/ping")
    Result<PingResponse> ping();
}
