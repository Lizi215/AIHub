package com.aihub.asset.service.impl;

import com.aihub.api.event.AssetUploadedEvent;
import com.aihub.api.event.EventTopics;
import com.aihub.asset.entity.AssetTagEntity;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.entity.EventOutboxEntity;
import com.aihub.asset.entity.TagEntity;
import com.aihub.asset.mapper.AssetTagMapper;
import com.aihub.asset.mapper.EventOutboxMapper;
import com.aihub.asset.mapper.TagMapper;
import com.aihub.asset.service.OutboxEventService;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.outbox.EventDispatcher;
import com.aihub.common.outbox.OutboxDispatchSupport;
import com.aihub.common.outbox.OutboxMessage;
import com.aihub.common.result.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 出站事件写入实现。
 *
 * <p>{@code MANDATORY} 传播行为强制要求已存在事务，保证事件与业务写入同生共死——这是
 * outbox 模式成立的前提，靠注释约束不可靠。
 *
 * <p>写入后事务提交时立即尝试投递（docs/SEARCH.md §1）：投递失败不抛出，消息已在 outbox 表里，
 * 定时中继会重投；这里抛出只会让调用方误以为发布失败，而数据其实已经改完了。
 *
 * <p>{@link EventDispatcher} 用 {@link ObjectProvider} 取：它只在 {@code outbox.enabled=true} 时存在，
 * 用构造注入会强制依赖，让"无 MQ 也能启动"这条退化路径失效。
 */
@Service
public class OutboxEventServiceImpl implements OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventServiceImpl.class);

    /** 出站状态：待投递 */
    private static final String STATUS_PENDING = "PENDING";

    /** 出站状态：已投递 */
    private static final String STATUS_SENT = "SENT";

    private final EventOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<EventDispatcher> dispatcherProvider;
    private final AssetTagMapper assetTagMapper;
    private final TagMapper tagMapper;

    public OutboxEventServiceImpl(EventOutboxMapper outboxMapper,
                                  ObjectMapper objectMapper,
                                  ObjectProvider<EventDispatcher> dispatcherProvider,
                                  AssetTagMapper assetTagMapper,
                                  TagMapper tagMapper) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.dispatcherProvider = dispatcherProvider;
        this.assetTagMapper = assetTagMapper;
        this.tagMapper = tagMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendAssetUploaded(AssetEntity asset, String version, String readme, long opVersion) {
        String eventId = UUID.randomUUID().toString().replace("-", "");

        AssetUploadedEvent event = new AssetUploadedEvent(
                eventId,
                EventTopics.CURRENT_EVENT_VERSION,
                opVersion,
                asset.getId(),
                asset.getOwnerId(),
                asset.getName(),
                asset.getSummary(),
                asset.getCategory(),
                asset.getStatus(),
                version,
                readme,
                loadTagNames(asset.getId()),
                System.currentTimeMillis());

        EventOutboxEntity row = new EventOutboxEntity();
        row.setEventId(eventId);
        row.setEventType(EventTopics.ASSET_UPLOADED);
        row.setEventVersion(EventTopics.CURRENT_EVENT_VERSION);
        row.setOpVersion(opVersion);
        row.setAggregateId(asset.getId());
        row.setTopic(EventTopics.ASSET_TOPIC);
        row.setTag(EventTopics.ASSET_UPLOADED);
        row.setStatus(STATUS_PENDING);
        row.setRetryCount(0);
        row.setDeleted(BaseEntity.NOT_DELETED);

        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // 序列化失败必须让整个事务回滚：宁可这次发布失败，也不要留下"状态改了但事件是残的"
            log.error("failed to serialize asset uploaded event, assetId={}", asset.getId(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "failed to serialize event payload");
        }

        outboxMapper.insert(row);
        log.info("outbox event appended, eventId={}, assetId={}, opVersion={}", eventId, asset.getId(), opVersion);

        EventDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
            // 提交后立即投递：绝大多数事件秒级送达；失败留给定时中继重投。
            // 投递成功要顺手把行标记 SENT，否则中继下一轮会重复投递。
            Long rowId = row.getId();
            OutboxDispatchSupport.dispatchAfterCommit(dispatcher,
                    new OutboxMessage(row.getEventId(), row.getTopic(), row.getTag(), row.getPayload()),
                    () -> markSent(rowId));
        }
    }

    /**
     * 资源当前的标签名列表（随事件下发，消费端直接写入 ES tags 字段，无需回查）。
     */
    private List<String> loadTagNames(Long assetId) {
        List<Long> tagIds = assetTagMapper.selectList(new LambdaQueryWrapper<AssetTagEntity>()
                .eq(AssetTagEntity::getAssetId, assetId)
                .eq(AssetTagEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream().map(AssetTagEntity::getTagId).toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tagMapper.selectList(new LambdaQueryWrapper<TagEntity>()
                .in(TagEntity::getId, tagIds)
                .eq(TagEntity::getDeleted, BaseEntity.NOT_DELETED))
                .stream().map(TagEntity::getName).sorted().toList();
    }

    /**
     * 立即投递成功后标记 SENT。
     *
     * <p>带 {@code status = PENDING} 条件更新：中继可能正在同一时刻投这一行，
     * 条件更新保证只有一方真正"认领"成功，另一方影响行数为 0。
     */
    private void markSent(Long rowId) {
        EventOutboxEntity update = new EventOutboxEntity();
        update.setStatus(STATUS_SENT);
        int affected = outboxMapper.update(update, new LambdaQueryWrapper<EventOutboxEntity>()
                .eq(EventOutboxEntity::getId, rowId)
                .eq(EventOutboxEntity::getStatus, STATUS_PENDING));
        if (affected == 0) {
            log.debug("outbox row already claimed by relay, rowId={}", rowId);
        }
    }
}
