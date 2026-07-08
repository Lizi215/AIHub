package com.aihub.api.dto;

/**
 * 服务间 ping 响应。
 *
 * @param service  响应服务的 spring.application.name
 * @param instance 服务实例标识（端口），用于确认负载均衡选中了哪个实例
 * @param traceId  接收到的 traceId，用于证明跨服务透传生效
 * @param serverTime 服务端时间，ISO-8601 字符串
 */
public record PingResponse(String service, String instance, String traceId, String serverTime) {
}
