package com.aihub.user.consumer;

import com.aihub.api.event.DownloadCompletedEvent;
import com.aihub.api.event.EventTopics;
import com.aihub.user.service.DownloadRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * 下载记录消费者：解析后委托 service，幂等在 service 内。
 */
@ExtendWith(MockitoExtension.class)
class DownloadRecordConsumerTest {

    @Mock
    private DownloadRecordService downloadRecordService;

    private DownloadRecordConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new DownloadRecordConsumer(downloadRecordService, objectMapper);
    }

    @Test
    @DisplayName("解析事件并委托落库")
    void shouldDelegateToDownloadRecordService() {
        consumer.onMessage(payload("evt-1", 7L, 100L, "1.0.0"));

        // occurredAt 是解析时刻的时间戳，用 captor 而不是比较整个对象
        ArgumentCaptor<DownloadCompletedEvent> captor =
                ArgumentCaptor.forClass(DownloadCompletedEvent.class);
        verify(downloadRecordService).handleDownloadCompleted(captor.capture());
        DownloadCompletedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.assetId()).isEqualTo(100L);
        assertThat(event.version()).isEqualTo("1.0.0");
        assertThat(event.eventVersion()).isEqualTo(EventTopics.CURRENT_EVENT_VERSION);
    }

    @Test
    @DisplayName("消息体不可解析 → 抛错交给 MQ 重试")
    void shouldFailOnUnparsablePayload() {
        assertThatThrownBy(() -> consumer.onMessage("{not json"))
                .isInstanceOf(IllegalStateException.class);
    }

    private String payload(String eventId, long userId, long assetId, String version) {
        DownloadCompletedEvent event = new DownloadCompletedEvent(
                eventId, EventTopics.CURRENT_EVENT_VERSION, userId, assetId, version,
                System.currentTimeMillis());
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
