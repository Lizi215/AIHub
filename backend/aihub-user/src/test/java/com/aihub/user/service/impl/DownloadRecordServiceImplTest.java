package com.aihub.user.service.impl;

import com.aihub.api.event.DownloadCompletedEvent;
import com.aihub.common.event.IdempotencyGuard;
import com.aihub.user.entity.DownloadRecordEntity;
import com.aihub.user.mapper.DownloadRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下载记录消费逻辑：幂等与失败释放。
 */
@ExtendWith(MockitoExtension.class)
class DownloadRecordServiceImplTest {

    private static final String EVENT_ID = "evt-dl-1";
    private static final long USER_ID = 7L;
    private static final long ASSET_ID = 100L;
    /** 2026-09-23T10:00:00Z */
    private static final long OCCURRED_AT = 1790157600000L;

    @Mock
    private DownloadRecordMapper downloadRecordMapper;

    private DownloadRecordServiceImpl service;

    @Test
    @DisplayName("首次消费落库，下载时间取事件时间而不是落库时间")
    void shouldInsertRecordOnFirstConsume() {
        service = new DownloadRecordServiceImpl(downloadRecordMapper, guardReturning(true));
        DownloadCompletedEvent event = event();

        assertThat(service.handleDownloadCompleted(event)).isTrue();

        ArgumentCaptor<DownloadRecordEntity> captor = ArgumentCaptor.forClass(DownloadRecordEntity.class);
        verify(downloadRecordMapper).insert(captor.capture());
        DownloadRecordEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getAssetId()).isEqualTo(ASSET_ID);
        assertThat(saved.getVersion()).isEqualTo("1.0.0");
        assertThat(saved.getDeleted()).isZero();
        assertThat(saved.getOccurredAt()).isEqualTo(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(OCCURRED_AT), ZoneId.systemDefault()));
    }

    @Test
    @DisplayName("重复事件被跳过：MQ 至少一次投递，不幂等就会写出重复记录")
    void shouldSkipDuplicateEvent() {
        service = new DownloadRecordServiceImpl(downloadRecordMapper, guardReturning(false));

        assertThat(service.handleDownloadCompleted(event())).isFalse();

        verify(downloadRecordMapper, never()).insert(any(DownloadRecordEntity.class));
    }

    @Test
    @DisplayName("落库失败必须释放幂等标记，否则重试会被当成重复消费而永久丢数据")
    void shouldReleaseGuardWhenPersistFails() {
        AtomicReference<String> released = new AtomicReference<>();
        IdempotencyGuard guard = new IdempotencyGuard(null, null) {
            @Override
            public boolean tryAcquire(String eventId) {
                return true;
            }

            @Override
            public void release(String eventId) {
                released.set(eventId);
            }
        };
        service = new DownloadRecordServiceImpl(downloadRecordMapper, guard);
        when(downloadRecordMapper.insert(any(DownloadRecordEntity.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.handleDownloadCompleted(event()))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

        assertThat(released.get()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("空事件直接忽略，不碰 Redis 也不落库")
    void shouldIgnoreNullEvent() {
        service = new DownloadRecordServiceImpl(downloadRecordMapper, guardReturning(true));

        assertThat(service.handleDownloadCompleted(null)).isFalse();

        verify(downloadRecordMapper, never()).insert(any(DownloadRecordEntity.class));
    }

    private DownloadCompletedEvent event() {
        return new DownloadCompletedEvent(EVENT_ID, 1, USER_ID, ASSET_ID, "1.0.0", OCCURRED_AT);
    }

    private IdempotencyGuard guardReturning(boolean acquired) {
        return new IdempotencyGuard(null, null) {
            @Override
            public boolean tryAcquire(String eventId) {
                return acquired;
            }

            @Override
            public void release(String eventId) {
                // 本组用例不验证释放行为
            }
        };
    }
}
