package com.aihub.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * AI 服务：Spring AI、Agent Orchestrator、Tool Calling。
 *
 * <p>架构铁律：AI Service 不持有业务数据。本服务只持有 {@code agent_task} 一张表，
 * Agent 取数一律走 {@code Agent → Tool → 业务服务 → 基础设施}，禁止直连 MySQL（业务表）
 * / Redis / ES / MinIO。
 *
 * <p>Feign 扫描只含被调用的外部服务契约：Asset / Community / Search 的查询契约，
 * 不含 {@code com.aihub.api.file}，因为 AI 服务不碰对象存储，文件字节始终由 Asset Service 编排
 * （docs/ARCHITECTURE.md §8.1）。
 */
@SpringBootApplication
@EnableFeignClients(
        basePackages = {"com.aihub.api.asset", "com.aihub.api.community", "com.aihub.api.search"},
        defaultConfiguration = com.aihub.api.config.FeignTraceConfig.class)
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
