package com.aihub.asset.outbox;

import com.aihub.common.outbox.EventDispatcher;
import com.aihub.common.outbox.RocketMQEventDispatcher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * outbox 投递装配。
 *
 * <p>投递实现只在开启 outbox 时才创建：{@code enabled=false} 表示本环境没有 MQ，
 * 此时若仍创建 dispatcher，每次业务提交都会打一条无用的投递失败日志，噪声会掩盖真正的问题。
 */
@Configuration(proxyBeanMethods = false)
public class OutboxDispatchConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "aihub.asset.outbox", name = "enabled", havingValue = "true")
    public EventDispatcher eventDispatcher(RocketMQTemplate rocketMQTemplate) {
        return new RocketMQEventDispatcher(rocketMQTemplate);
    }
}
