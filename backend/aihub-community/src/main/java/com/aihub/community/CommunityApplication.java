package com.aihub.community;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 社区服务：帖子、评论、点赞、收藏、Issue 追踪。
 *
 * <p>社区内容只存 authorId，展示昵称时经 {@code UserQueryApi} 跨服务取，不直接查 user 表。
 */
@SpringBootApplication
@EnableFeignClients(
        basePackages = {"com.aihub.api.user"},
        defaultConfiguration = com.aihub.api.config.FeignTraceConfig.class)
public class CommunityApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunityApplication.class, args);
    }
}
