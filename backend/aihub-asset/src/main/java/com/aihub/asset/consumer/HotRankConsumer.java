package com.aihub.asset.consumer;

import com.aihub.api.event.DownloadCompletedEvent;
import com.aihub.api.event.EventTopics;
import com.aihub.asset.service.AssetMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 热门榜消费者（docs/MESSAGING.md §1：{@code HotRankConsumer → Asset Service}）。
 *
 * <p>解析事件后调用 {@code recordDownload}（防刷量去重与 ZSet 累加都在 service 内）。
 * 计数只进 Redis，热门榜是派生数据、可随时以 MySQL 重建；失败交给 MQ 重试。
 */
@Component
@RocketMQMessageListener(
        topic = EventTopics.DOWNLOAD_TOPIC,
        selectorExpression = EventTopics.DOWNLOAD_COMPLETED,
        consumerGroup = "aihub-asset-hot-rank",
        maxReconsumeTimes = 3)
public class HotRankConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(HotRankConsumer.class);

    private final AssetMetricsService assetMetricsService;
    private final ObjectMapper objectMapper;

    public HotRankConsumer(AssetMetricsService assetMetricsService, ObjectMapper objectMapper) {
        this.assetMetricsService = assetMetricsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String payload) {
        DownloadCompletedEvent event = parse(payload);
        boolean counted = assetMetricsService.recordDownload(event.assetId(), event.userId(), event.version());
        log.info("download counted, eventId={}, assetId={}, counted={}",
                event.eventId(), event.assetId(), counted);
    }

    private DownloadCompletedEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, DownloadCompletedEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse download completed event", e);
        }
    }
}
