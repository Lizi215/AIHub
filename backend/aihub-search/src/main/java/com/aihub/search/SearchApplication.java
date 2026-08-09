package com.aihub.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 搜索服务，Elasticsearch 的唯一出口。
 *
 * <p>索引的建/更新/删除、搜索、高亮、筛选、排序、深分页全部只在本服务内；
 * 业务服务不得直连 ES，只能经 RocketMQ 事件驱动同步；
 * 本服务不持业务表（无 JDBC/MyBatis），对账所需数据经 Feign 向业务服务取。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients(
        basePackages = {"com.aihub.api.asset", "com.aihub.api.community", "com.aihub.api.user"},
        defaultConfiguration = com.aihub.api.config.FeignTraceConfig.class)
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
