package com.aihub.asset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 资源服务（核心业务服务）。
 *
 * <p>Feign 扫描只包含被调用的外部服务契约：{@code com.aihub.api.file}（File Service）。
 * 不含 {@code com.aihub.api.asset}——Asset 自己实现了 AssetPingApi，纳入扫描会多生成一个自身客户端。
 *
 * <p>{@code @EnableScheduling}：outbox 中继的定时兜底重投依赖它（docs/SEARCH.md §1）。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients(
        basePackages = {"com.aihub.api.file", "com.aihub.api.user"},
        defaultConfiguration = com.aihub.api.config.FeignTraceConfig.class)
public class AssetApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssetApplication.class, args);
    }
}
