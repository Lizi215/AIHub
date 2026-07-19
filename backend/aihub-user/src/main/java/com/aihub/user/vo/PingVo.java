package com.aihub.user.vo;

/**
 * 连通性探测响应（对外）。服务间 DTO 不透出给前端，这里单独建模。
 *
 * <p>{@code downstream*} 字段用于在单次调用里直接观察 Feign 调用结果。
 */
public record PingVo(
        String service,
        String instance,
        String traceId,
        String serverTime,
        String downstreamService,
        String downstreamInstance
) {
}
