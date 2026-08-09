package com.aihub.search.consumer;

import com.aihub.api.dto.AssetIndexDoc;
import com.aihub.api.event.AssetUploadedEvent;
import com.aihub.api.event.EventTopics;
import com.aihub.common.event.EventVersionPolicy;
import com.aihub.common.event.IdempotencyGuard;
import com.aihub.common.result.ErrorCode;
import com.aihub.search.index.IndexDocuments;
import com.aihub.search.index.IndexManager;
import com.aihub.search.index.SearchDocType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资源索引消费者。
 *
 * <p>三步缺一不可，顺序也不能变：先用 {@link IdempotencyGuard} 抢占（MQ 至少一次投递，
 * 抢不到直接 ACK）；再用 opVersion 防乱序，只有严格更大的版本才允许覆盖，旧事件后到就跳过；
 * 最后非 PUBLISHED 的状态必须删文档而不是写入，草稿进索引等于泄漏。
 *
 * <p>失败时释放幂等占用并抛异常交给 RocketMQ 重试，不吞异常，否则等于静默丢索引。
 */
@Component
@RocketMQMessageListener(
        topic = EventTopics.ASSET_TOPIC,
        selectorExpression = EventTopics.ASSET_UPLOADED,
        consumerGroup = "aihub-search-asset-index",
        maxReconsumeTimes = 3)
public class AssetIndexConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(AssetIndexConsumer.class);

    /** 与 Asset Service 的 AssetStatus.PUBLISHED 对应，只有它才进索引。 */
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    private final IndexManager indexManager;
    private final IdempotencyGuard idempotencyGuard;
    private final ObjectMapper objectMapper;

    public AssetIndexConsumer(IndexManager indexManager,
                              IdempotencyGuard idempotencyGuard,
                              ObjectMapper objectMapper) {
        this.indexManager = indexManager;
        this.idempotencyGuard = idempotencyGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String payload) {
        AssetUploadedEvent event = parse(payload);
        if (!idempotencyGuard.tryAcquire(event.eventId())) {
            // 重复投递：已经处理过，直接 ACK
            log.info("duplicate asset event skipped, eventId={}", event.eventId());
            return;
        }
        try {
            apply(event);
        } catch (RuntimeException e) {
            // 失败要释放幂等占用，否则重试会被误判为已消费过而空转
            idempotencyGuard.release(event.eventId());
            throw e;
        }
    }

    private void apply(AssetUploadedEvent event) {
        String id = String.valueOf(event.assetId());
        if (!STATUS_PUBLISHED.equals(event.status())) {
            // 草稿 / 已下架：从索引移除，绝不留档
            indexManager.deleteDocument(SearchDocType.ASSET, id);
            log.info("asset removed from index, assetId={}, status={}", event.assetId(), event.status());
            return;
        }
        Long applied = indexManager.readOpVersion(SearchDocType.ASSET, id);
        if (!EventVersionPolicy.shouldApply(applied, event.opVersion())) {
            log.info("stale asset event skipped, assetId={}, applied={}, incoming={}",
                    event.assetId(), applied, event.opVersion());
            return;
        }
        indexManager.upsert(SearchDocType.ASSET, id, IndexDocuments.asset(new AssetIndexDoc(
                id,
                event.name(),
                event.summary(),
                event.readme(),
                event.tags() == null ? List.of() : event.tags(),
                event.category(),
                String.valueOf(event.ownerId()),
                event.status(),
                event.opVersion(),
                0L,
                toIso(event.occurredAt()),
                toIso(event.occurredAt()),
                // 事件体里的 version 是这次发布后的最新版本，直接进索引
                event.version())), event.opVersion());
        log.info("asset indexed, assetId={}, version={}, opVersion={}",
                event.assetId(), event.version(), event.opVersion());
    }

    private AssetUploadedEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, AssetUploadedEvent.class);
        } catch (Exception e) {
            // 解析不了说明消息体与契约不一致，抛错让它进重试/死信，不要静默丢弃
            throw new IllegalStateException("failed to parse " + ErrorCode.SEARCH_INDEX_OPERATION_FAILED.getCode()
                    + ": asset uploaded event", e);
        }
    }

    /**
     * 事件时间是 epoch millis，索引字段是 date 类型。
     *
     * <p>用 ISO-8601 字符串而不是时间戳数字：可读性更好，也不会因毫秒/秒搞混而出错。
     */
    private String toIso(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }
}
