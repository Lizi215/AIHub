package com.aihub.community.service.impl;

import com.aihub.api.event.EventTopics;
import com.aihub.api.event.PostCreatedEvent;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.outbox.EventDispatcher;
import com.aihub.common.outbox.OutboxDispatchSupport;
import com.aihub.common.outbox.OutboxMessage;
import com.aihub.common.result.ErrorCode;
import com.aihub.community.entity.EventOutboxEntity;
import com.aihub.community.entity.PostEntity;
import com.aihub.community.mapper.EventOutboxMapper;
import com.aihub.community.service.OutboxEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 社区侧出站事件写入实现。
 *
 * <p>结构与 Asset 侧保持一致（同事务写入 + 提交后立即投递 + 失败留给中继重投），
 * 这样两边的可靠性语义相同，不会出现"资源的事件能秒到、帖子的事件要等一分钟"这种差异。
 */
@Service
public class OutboxEventServiceImpl implements OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventServiceImpl.class);

    private static final String STATUS_PENDING = "PENDING";

    /** 帖子只有创建这一个事件，业务对象版本恒为 1（docs/MESSAGING.md 只定义了 PostCreatedEvent） */
    private static final long POST_OP_VERSION = 1L;

    private final EventOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<EventDispatcher> dispatcherProvider;

    public OutboxEventServiceImpl(EventOutboxMapper outboxMapper,
                                  ObjectMapper objectMapper,
                                  ObjectProvider<EventDispatcher> dispatcherProvider) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.dispatcherProvider = dispatcherProvider;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendPostCreated(PostEntity post) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        PostCreatedEvent event = new PostCreatedEvent(
                eventId,
                EventTopics.CURRENT_EVENT_VERSION,
                POST_OP_VERSION,
                post.getId(),
                post.getAuthorId(),
                post.getType(),
                post.getTitle(),
                post.getContent(),
                post.getAssetId(),
                System.currentTimeMillis());

        EventOutboxEntity row = new EventOutboxEntity();
        row.setEventId(eventId);
        row.setEventType(EventTopics.POST_CREATED);
        row.setEventVersion(EventTopics.CURRENT_EVENT_VERSION);
        row.setOpVersion(POST_OP_VERSION);
        row.setAggregateId(post.getId());
        row.setTopic(EventTopics.COMMUNITY_TOPIC);
        row.setTag(EventTopics.POST_CREATED);
        row.setStatus(STATUS_PENDING);
        row.setRetryCount(0);
        row.setDeleted(BaseEntity.NOT_DELETED);
        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // 序列化失败让整个事务回滚：宁可这次发帖失败，也不要留下"帖子在但事件是残的"
            log.error("failed to serialize post created event, postId={}", post.getId(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "failed to serialize event payload");
        }
        outboxMapper.insert(row);
        log.info("outbox event appended, eventId={}, postId={}", eventId, post.getId());

        EventDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
            OutboxDispatchSupport.dispatchAfterCommit(dispatcher,
                    new OutboxMessage(row.getEventId(), row.getTopic(), row.getTag(), row.getPayload()),
                    () -> markSent(row.getId()));
        }
    }

    private void markSent(Long rowId) {
        EventOutboxEntity update = new EventOutboxEntity();
        update.setStatus("SENT");
        outboxMapper.update(update,
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EventOutboxEntity>()
                        .eq(EventOutboxEntity::getId, rowId)
                        .eq(EventOutboxEntity::getStatus, STATUS_PENDING));
    }
}
