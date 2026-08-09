package com.aihub.search.consumer;

import com.aihub.api.dto.PostIndexDoc;
import com.aihub.api.event.EventTopics;
import com.aihub.api.event.PostCreatedEvent;
import com.aihub.common.event.EventVersionPolicy;
import com.aihub.common.event.IdempotencyGuard;
import com.aihub.search.index.IndexDocuments;
import com.aihub.search.index.IndexManager;
import com.aihub.search.index.SearchDocType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 帖子索引消费者。
 *
 * <p>与资源索引同样是幂等 → 防乱序 → 写入三步。帖子的 opVersion 恒为 1（只有创建事件），
 * 防乱序只在索引已有更高版本时生效，但仍保持与资源一致的结构，将来加编辑事件无需改这里。
 */
@Component
@RocketMQMessageListener(
        topic = EventTopics.COMMUNITY_TOPIC,
        selectorExpression = EventTopics.POST_CREATED,
        consumerGroup = "aihub-search-post-index",
        maxReconsumeTimes = 3)
public class PostIndexConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PostIndexConsumer.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final IndexManager indexManager;
    private final IdempotencyGuard idempotencyGuard;
    private final ObjectMapper objectMapper;

    public PostIndexConsumer(IndexManager indexManager,
                             IdempotencyGuard idempotencyGuard,
                             ObjectMapper objectMapper) {
        this.indexManager = indexManager;
        this.idempotencyGuard = idempotencyGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String payload) {
        PostCreatedEvent event = parse(payload);
        if (!idempotencyGuard.tryAcquire(event.eventId())) {
            log.info("duplicate post event skipped, eventId={}", event.eventId());
            return;
        }
        try {
            String id = String.valueOf(event.postId());
            Long applied = indexManager.readOpVersion(SearchDocType.POST, id);
            if (!EventVersionPolicy.shouldApply(applied, event.opVersion())) {
                log.info("stale post event skipped, postId={}, applied={}, incoming={}",
                        event.postId(), applied, event.opVersion());
                return;
            }
            String time = toIso(event.occurredAt());
            indexManager.upsert(SearchDocType.POST, id, IndexDocuments.post(new PostIndexDoc(
                    id,
                    event.type(),
                    event.title(),
                    event.content(),
                    String.valueOf(event.authorId()),
                    event.assetId() == null ? null : String.valueOf(event.assetId()),
                    event.opVersion(),
                    time,
                    time)), event.opVersion());
            log.info("post indexed, postId={}, type={}, opVersion={}",
                    event.postId(), event.type(), event.opVersion());
        } catch (RuntimeException e) {
            idempotencyGuard.release(event.eventId());
            throw e;
        }
    }

    private PostCreatedEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, PostCreatedEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse post created event", e);
        }
    }

    private String toIso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
                .toLocalDateTime().format(ISO);
    }
}
