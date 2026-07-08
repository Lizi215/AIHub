package com.aihub.common.outbox;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;

/**
 * 基于 RocketMQ 的事件投递实现。放在 `aihub-common` 是因为 Asset 与 Community 都要投递事件
 * （docs/SEARCH.md §1），各写一份迟早会在失败判定、消息属性上漂移。
 *
 * <p>投递失败必须抛异常（而不是返回 false），中继据此决定重试与退避；消息带 {@code KEYS = eventId}，
 * 出问题时能用 `mqadmin queryMsgByKey` 直接定位到消息。
 *
 * <p>只有 SEND_OK 才算成功：超时类状态（FLUSH_DISK_TIMEOUT 等）说明消息可能没落盘，当成成功就会丢事件。
 */
public class RocketMQEventDispatcher implements EventDispatcher {

    private final RocketMQTemplate rocketMQTemplate;

    public RocketMQEventDispatcher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void dispatch(OutboxMessage message) {
        String destination = message.topic() + ":" + message.tag();
        SendResult result = rocketMQTemplate.syncSend(destination,
                MessageBuilder.withPayload(message.payload())
                        // 用 eventId 作为消息 KEYS，便于按 id 反查消息
                        .setHeader("KEYS", message.eventId())
                        .build());
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            // 不把非 SEND_OK 当成功：宁可让中继重投（消费端幂等），也不要丢事件
            throw new IllegalStateException("rocketmq send not ok, eventId=" + message.eventId()
                    + ", status=" + (result == null ? "null" : result.getSendStatus()));
        }
    }
}
