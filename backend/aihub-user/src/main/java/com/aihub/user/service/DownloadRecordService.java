package com.aihub.user.service;

import com.aihub.api.event.DownloadCompletedEvent;

/**
 * 下载记录。
 */
public interface DownloadRecordService {

    /**
     * 处理下载完成事件，落一条下载记录。
     *
     * <p>MQ 至少一次投递，同一事件可能重复消费，必须幂等。
     *
     * @return {@code true} 表示本次真正落库；{@code false} 表示重复事件已跳过
     */
    boolean handleDownloadCompleted(DownloadCompletedEvent event);
}
