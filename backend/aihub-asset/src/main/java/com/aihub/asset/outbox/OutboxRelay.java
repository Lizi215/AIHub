package com.aihub.asset.outbox;

import com.aihub.asset.entity.EventOutboxEntity;
import com.aihub.asset.mapper.EventOutboxMapper;
import com.aihub.common.outbox.EventDispatcher;
import com.aihub.common.outbox.OutboxMessage;
import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * outbox 中继：把本地消息表里待投递的记录推到 MQ。
 *
 * <p>业务事务保证「事件一定落库」，中继保证「落库的事件最终一定被投递」。投递成功才标记 SENT，
 * 失败保持待投递并退避重试；单条失败不阻断同批其它记录，否则一条坏消息会卡死整个队列；
 * 重试超过上限转 FAILED 停止重试。
 *
 * <p>MQ 实现尚未接入，因此本 Bean 默认不创建（{@code aihub.asset.outbox.enabled=true} 且提供
 * {@link EventDispatcher} 时才启用）。
 */
@Component
@ConditionalOnProperty(prefix = "aihub.asset.outbox", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";

    private final EventOutboxMapper outboxMapper;
    private final EventDispatcher dispatcher;
    private final OutboxProperties properties;

    public OutboxRelay(EventOutboxMapper outboxMapper, EventDispatcher dispatcher, OutboxProperties properties) {
        this.outboxMapper = outboxMapper;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    /**
     * 执行一轮投递。
     *
     * @return 本轮成功投递的条数，便于测试与观测
     */
    public int relayOnce() {
        // 走分页插件而不是手写 LIMIT（docs/BACKEND.md §4）；searchCount=false 不需要总数
        Page<EventOutboxEntity> page = new Page<>(1, properties.getBatchSize(), false);
        List<EventOutboxEntity> candidates = outboxMapper.selectPage(page, pendingQuery()).getRecords();
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (EventOutboxEntity row : candidates) {
            if (dispatchOne(row)) {
                sent++;
            }
        }
        if (sent > 0 || !candidates.isEmpty()) {
            log.info("outbox relay finished, candidates={}, sent={}", candidates.size(), sent);
        }
        return sent;
    }

    private boolean dispatchOne(EventOutboxEntity row) {
        try {
            dispatcher.dispatch(new OutboxMessage(row.getEventId(), row.getTopic(), row.getTag(), row.getPayload()));
            markSent(row);
            return true;
        } catch (Exception e) {
            // 单条失败吞在这里，让循环继续处理后续记录，否则一条坏消息会卡住整批
            markFailed(row, e);
            return false;
        }
    }

    private void markSent(EventOutboxEntity row) {
        EventOutboxEntity update = new EventOutboxEntity();
        update.setId(row.getId());
        update.setStatus(STATUS_SENT);
        outboxMapper.updateById(update);
    }

    private void markFailed(EventOutboxEntity row, Exception cause) {
        int retryCount = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
        EventOutboxEntity update = new EventOutboxEntity();
        update.setId(row.getId());
        update.setRetryCount(retryCount);

        if (retryCount >= properties.getMaxRetryCount()) {
            // 超过上限不再重试，留待人工/对账处理，避免无限重试拖垮中继
            update.setStatus(STATUS_FAILED);
            log.error("outbox event permanently failed after {} retries, eventId={}",
                    retryCount, row.getEventId(), cause);
        } else {
            update.setStatus(STATUS_PENDING);
            update.setNextRetryAt(LocalDateTime.now().plus(backoff(retryCount)));
            log.warn("outbox dispatch failed, will retry, eventId={}, retryCount={}",
                    row.getEventId(), retryCount, cause);
        }
        outboxMapper.updateById(update);
    }

    /** 指数退避：base * 2^(retry-1)，并以 maxBackoff 封顶 */
    private java.time.Duration backoff(int retryCount) {
        long multiplier = 1L << Math.min(retryCount - 1, 16);
        java.time.Duration backoff = properties.getBaseBackoff().multipliedBy(multiplier);
        return backoff.compareTo(properties.getMaxBackoff()) > 0 ? properties.getMaxBackoff() : backoff;
    }

    private LambdaQueryWrapper<EventOutboxEntity> pendingQuery() {
        return new LambdaQueryWrapper<EventOutboxEntity>()
                .eq(EventOutboxEntity::getDeleted, BaseEntity.NOT_DELETED)
                .eq(EventOutboxEntity::getStatus, STATUS_PENDING)
                // next_retry_at 为空表示尚未重试过，应立即投递
                .and(wrapper -> wrapper.isNull(EventOutboxEntity::getNextRetryAt)
                        .or()
                        .le(EventOutboxEntity::getNextRetryAt, LocalDateTime.now()))
                .orderByAsc(EventOutboxEntity::getCreateTime);
    }
}
