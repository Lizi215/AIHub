package com.aihub.community.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 本地消息表映射（`event_outbox`）。
 *
 * <p>业务写入与事件写入必须同一事务落库，否则会出现「状态改了但消息丢了」。
 *
 * <p>与 Asset Service 共用同一张表（docs/SEARCH.md §1）：行里自带 topic/tag 与完整 payload，
 * 谁写入、谁中继都不影响投递正确性。
 */
@TableName("event_outbox")
public class EventOutboxEntity extends BaseEntity {

    private String eventId;

    private String eventType;

    private Integer eventVersion;

    /** 聚合维度的操作版本，消费端据此防乱序 */
    private Long opVersion;

    private Long aggregateId;

    private String topic;

    private String tag;

    private String payload;

    /** PENDING / SENT / FAILED */
    private String status;

    private Integer retryCount;

    /** 下次可投递时间；为空表示尚未重试过，应立即投递 */
    private LocalDateTime nextRetryAt;

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Integer getEventVersion() {
        return eventVersion;
    }

    public void setEventVersion(Integer eventVersion) {
        this.eventVersion = eventVersion;
    }

    public Long getOpVersion() {
        return opVersion;
    }

    public void setOpVersion(Long opVersion) {
        this.opVersion = opVersion;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Long aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }
}
