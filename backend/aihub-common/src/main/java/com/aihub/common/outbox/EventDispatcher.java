package com.aihub.common.outbox;

/**
 * 事件投递能力。
 *
 * <p>RocketMQ 是目标实现（尚未接入）。
 * 抽成接口的目的是让 {@link OutboxRelay} 的候选处理、退避与状态推进逻辑可以脱离 MQ 单测。
 *
 * <p>实现约定：投递失败必须抛异常（而不是返回 false），中继据此决定重试与退避。
 */
@FunctionalInterface
public interface EventDispatcher {

    /**
     * 投递一条事件。
     *
     * @throws Exception 投递失败，由中继记录重试
     */
    void dispatch(OutboxMessage message) throws Exception;
}
