package com.aihub.search.consumer;

import com.aihub.api.event.AssetUploadedEvent;
import com.aihub.api.event.EventTopics;
import com.aihub.common.event.IdempotencyGuard;
import com.aihub.search.index.IndexManager;
import com.aihub.search.index.SearchDocType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资源索引消费者：草稿搜不到、重放同一条消息不产生重复文档、opVersion 防乱序。
 */
@ExtendWith(MockitoExtension.class)
class AssetIndexConsumerTest {

    private static final long ASSET_ID = 100L;

    @Mock
    private IndexManager indexManager;
    @Mock
    private IdempotencyGuard idempotencyGuard;

    private AssetIndexConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new AssetIndexConsumer(indexManager, idempotencyGuard, objectMapper);
    }

    @Test
    @DisplayName("已发布资源 → 写入索引，且 opVersion 以数字形式落库（防乱序依赖它做数值比较）")
    void shouldIndexPublishedAsset() {
        when(idempotencyGuard.tryAcquire("evt-1")).thenReturn(true);
        when(indexManager.readOpVersion(SearchDocType.ASSET, String.valueOf(ASSET_ID))).thenReturn(null);

        consumer.onMessage(payload("evt-1", "PUBLISHED", 1L));

        ArgumentCaptor<Map> docCaptor = ArgumentCaptor.forClass(Map.class);
        verify(indexManager).upsert(eq(SearchDocType.ASSET), eq(String.valueOf(ASSET_ID)), docCaptor.capture(), anyLong());
        assertThat(docCaptor.getValue().get("opVersion")).isInstanceOf(Number.class);
        assertThat(docCaptor.getValue().get("title")).isEqualTo("Spring Cloud 配置技能");
        assertThat(docCaptor.getValue().get("status")).isEqualTo("PUBLISHED");
        // 版本号要进索引，Agent 的 search_assets 输出契约里有 latestVersion
        assertThat(docCaptor.getValue().get("latestVersion")).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("重复投递（同一 eventId）→ 直接跳过，不重复写文档")
    void shouldSkipDuplicateEvent() {
        when(idempotencyGuard.tryAcquire("evt-dup")).thenReturn(false);

        consumer.onMessage(payload("evt-dup", "PUBLISHED", 1L));

        verify(indexManager, never()).upsert(any(), any(), any(), anyLong());
        verify(indexManager, never()).readOpVersion(any(), any());
    }

    @Test
    @DisplayName("乱序的旧事件 → 跳过，不能把索引回退到过期内容")
    void shouldSkipStaleEvent() {
        when(idempotencyGuard.tryAcquire("evt-old")).thenReturn(true);
        when(indexManager.readOpVersion(SearchDocType.ASSET, String.valueOf(ASSET_ID))).thenReturn(5L);

        consumer.onMessage(payload("evt-old", "PUBLISHED", 3L));

        verify(indexManager, never()).upsert(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("草稿态 → 从索引删除，绝不把草稿写进索引")
    void shouldDeleteDraftFromIndex() {
        when(idempotencyGuard.tryAcquire("evt-draft")).thenReturn(true);

        consumer.onMessage(payload("evt-draft", "DRAFT", 1L));

        verify(indexManager).deleteDocument(SearchDocType.ASSET, String.valueOf(ASSET_ID));
        verify(indexManager, never()).upsert(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("处理失败 → 释放幂等占用并抛出，交给 MQ 重试（不能吞异常造成静默丢索引）")
    void shouldReleaseAndRethrowOnFailure() {
        when(idempotencyGuard.tryAcquire("evt-fail")).thenReturn(true);
        when(indexManager.readOpVersion(any(), any())).thenThrow(new RuntimeException("es down"));

        assertThatThrownBy(() -> consumer.onMessage(payload("evt-fail", "PUBLISHED", 1L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("es down");

        verify(idempotencyGuard).release("evt-fail");
    }

    @Test
    @DisplayName("消息体不可解析 → 抛错进重试/死信，而不是静默丢弃")
    void shouldFailOnUnparsablePayload() {
        assertThatThrownBy(() -> consumer.onMessage("{not json"))
                .isInstanceOf(IllegalStateException.class);
    }

    private String payload(String eventId, String status, long opVersion) {
        AssetUploadedEvent event = new AssetUploadedEvent(
                eventId, EventTopics.CURRENT_EVENT_VERSION, opVersion, ASSET_ID, 1L,
                "Spring Cloud 配置技能", "为 Spring Cloud 项目提供常用配置模板", "SKILL",
                status, "1.0.0", "README 内容", List.of("spring"), System.currentTimeMillis());
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
