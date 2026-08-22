package com.aihub.community.config;

import com.aihub.common.outbox.EventDispatcher;
import com.aihub.common.outbox.RocketMQEventDispatcher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 社区侧出站事件投递装配。
 *
 * <p>与 Asset 侧同样的处理：只在开启 outbox 时创建 dispatcher，避免"没有 MQ 的环境"
 * 还要为每次发帖打一条无用的投递失败日志。
 */
@Configuration(proxyBeanMethods = false)
public class CommunityOutboxConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "aihub.community.outbox", name = "enabled", havingValue = "true")
    public EventDispatcher eventDispatcher(RocketMQTemplate rocketMQTemplate) {
        return new RocketMQEventDispatcher(rocketMQTemplate);
    }
}
