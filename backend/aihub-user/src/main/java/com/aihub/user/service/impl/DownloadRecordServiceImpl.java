package com.aihub.user.service.impl;

import com.aihub.api.event.DownloadCompletedEvent;
import com.aihub.common.event.IdempotencyGuard;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.user.entity.DownloadRecordEntity;
import com.aihub.user.mapper.DownloadRecordMapper;
import com.aihub.user.service.DownloadRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 下载记录实现。
 *
 * <p>MQ 至少一次投递，靠幂等守卫去重；落库失败时要释放幂等标记，
 * 否则重试会被判为已消费，这条记录永久丢失。
 */
@Service
public class DownloadRecordServiceImpl implements DownloadRecordService {

    private static final Logger log = LoggerFactory.getLogger(DownloadRecordServiceImpl.class);

    private final DownloadRecordMapper downloadRecordMapper;
    private final IdempotencyGuard idempotencyGuard;

    public DownloadRecordServiceImpl(DownloadRecordMapper downloadRecordMapper,
                                     IdempotencyGuard idempotencyGuard) {
        this.downloadRecordMapper = downloadRecordMapper;
        this.idempotencyGuard = idempotencyGuard;
    }

    @Override
    @Transactional
    public boolean handleDownloadCompleted(DownloadCompletedEvent event) {
        if (event == null || !idempotencyGuard.tryAcquire(event.eventId())) {
            return false;
        }

        try {
            DownloadRecordEntity record = new DownloadRecordEntity();
            record.setUserId(event.userId());
            record.setAssetId(event.assetId());
            record.setVersion(event.version());
            // 用事件时间而非 now()，消息可能延迟，落库时间会让下载历史错位
            record.setOccurredAt(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.occurredAt()), ZoneId.systemDefault()));
            record.setDeleted(BaseEntity.NOT_DELETED);
            downloadRecordMapper.insert(record);
            log.info("download record saved, eventId={}, userId={}, assetId={}",
                    event.eventId(), event.userId(), event.assetId());
            return true;
        } catch (RuntimeException e) {
            // 落库失败要释放幂等标记，否则重试会被当成重复消费
            idempotencyGuard.release(event.eventId());
            throw e;
        }
    }
}
