package com.aihub.user.consumer;

import com.aihub.api.event.DownloadCompletedEvent;
import com.aihub.api.event.EventTopics;
import com.aihub.user.service.DownloadRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 下载记录消费者。
 *
 * <p>只做消息解析和转发，幂等在 {@link DownloadRecordService}。
 * 解析失败直接抛错交给 MQ 重试，吞掉就等于静默丢记录。
 */
@Component
@RocketMQMessageListener(
        topic = EventTopics.DOWNLOAD_TOPIC,
        selectorExpression = EventTopics.DOWNLOAD_COMPLETED,
        consumerGroup = "aihub-user-download-record",
        maxReconsumeTimes = 3)
public class DownloadRecordConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(DownloadRecordConsumer.class);

    private final DownloadRecordService downloadRecordService;
    private final ObjectMapper objectMapper;

    public DownloadRecordConsumer(DownloadRecordService downloadRecordService, ObjectMapper objectMapper) {
        this.downloadRecordService = downloadRecordService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String payload) {
        DownloadCompletedEvent event = parse(payload);
        boolean applied = downloadRecordService.handleDownloadCompleted(event);
        log.info("download record handled, eventId={}, applied={}", event.eventId(), applied);
    }

    private DownloadCompletedEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, DownloadCompletedEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse download completed event", e);
        }
    }
}
