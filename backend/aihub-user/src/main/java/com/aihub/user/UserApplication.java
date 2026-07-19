package com.aihub.user;

import com.aihub.api.config.FeignTraceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 用户服务。
 *
 * <p>{@code defaultConfiguration} 显式指定 traceId 透传拦截器，不依赖默认包扫描，
 * 否则拦截器会静默失效导致链路断裂。
 */
@SpringBootApplication
@EnableFeignClients(
        basePackages = {"com.aihub.api.asset"},
        defaultConfiguration = FeignTraceConfig.class)
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
