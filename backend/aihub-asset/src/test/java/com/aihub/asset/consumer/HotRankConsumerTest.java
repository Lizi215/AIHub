package com.aihub.asset.consumer;

import com.aihub.api.event.DownloadCompletedEvent;
import com.aihub.api.event.EventTopics;
import com.aihub.asset.service.AssetMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 热门榜消费者：解析 → 委托 {@code recordDownload}（去重在 service 内）。
 */
@ExtendWith(MockitoExtension.class)
class HotRankConsumerTest {

    @Mock
    private AssetMetricsService assetMetricsService;

    private HotRankConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new HotRankConsumer(assetMetricsService, objectMapper);
    }

    @Test
    @DisplayName("解析事件并委托计数（防刷量去重在 service 内）")
    void shouldDelegateToMetricsService() {
        consumer.onMessage(payload("evt-1", 100L, 7L, "1.0.0"));

        verify(assetMetricsService).recordDownload(eq(100L), eq(7L), eq("1.0.0"));
    }

    @Test
    @DisplayName("消息体不可解析 → 抛错交给 MQ 重试，而不是静默丢计数")
    void shouldFailOnUnparsablePayload() {
        assertThatThrownBy(() -> consumer.onMessage("{not json"))
                .isInstanceOf(IllegalStateException.class);
    }

    private String payload(String eventId, long assetId, long userId, String version) {
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
