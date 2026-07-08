package com.aihub.common.outbox;

/**
 * 待投递事件的传输中立表示。
 *
 * <p>中继不依赖 outbox 表的字段结构，也不依赖具体 MQ，RocketMQ 只是 {@link EventDispatcher} 的一种实现。
 * 这样中继逻辑才能脱离 MQ 单测。
 */
public record OutboxMessage(String eventId, String topic, String tag, String payload) {
}
