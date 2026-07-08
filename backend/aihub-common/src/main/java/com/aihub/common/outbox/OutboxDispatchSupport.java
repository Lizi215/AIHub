package com.aihub.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「事务提交后立即投递」的统一实现（docs/SEARCH.md §1 写入路径第 5 步）。
 *
 * <p>必须在 afterCommit 而非提交前发消息：事务若回滚，消息已经发出，消费端会去查一条不存在的业务数据。
 *
 * <p>失败只记录、不抛出：消息已落库在 outbox 表，定时中继会重投（消费端幂等），
 * 提交后抛异常既回滚不了业务写入，又会让调用方误以为数据没改。
 */
public final class OutboxDispatchSupport {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchSupport.class);

    private OutboxDispatchSupport() {
    }

    /**
     * 在事务提交后投递一条事件，成功后回调 {@code onSuccess}（通常用于把 outbox 行标记为 SENT）。
     *
     * <p>不标记 SENT 的话，定时中继下一轮会再投一次；消费端幂等能保证数据不错，但属于纯粹的浪费。
     *
     * <p>当前线程没有活动事务时直接投递（此时也不存在「事务回滚但消息已发」的问题）。
     */
    public static void dispatchAfterCommit(EventDispatcher dispatcher, OutboxMessage message, Runnable onSuccess) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatchQuietly(dispatcher, message, onSuccess);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchQuietly(dispatcher, message, onSuccess);
            }
        });
    }

    private static void dispatchQuietly(EventDispatcher dispatcher, OutboxMessage message, Runnable onSuccess) {
        try {
            dispatcher.dispatch(message);
            onSuccess.run();
            log.info("outbox event dispatched immediately, eventId={}, topic={}",
                    message.eventId(), message.topic());
        } catch (Exception e) {
            // 留给定时中继重投；这里只保留证据，不要打断业务
            log.warn("immediate outbox dispatch failed, eventId={}, will be retried by relay: {}",
                    message.eventId(), e.getMessage());
        }
    }
}
