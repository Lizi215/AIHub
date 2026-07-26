package com.aihub.asset.service;

import com.aihub.asset.entity.AssetEntity;

/**
 * 出站事件写入（本地消息表）。
 */
public interface OutboxEventService {

    /**
     * 写入「资源已发布」事件。
     *
     * <p>必须在调用方的事务内执行（实现标了 {@code MANDATORY} 传播行为）。
     * 否则业务写入与事件写入失去原子性，会出现「状态改了但没有事件」或
     * 「有事件但状态回滚」，两种都很难排查。
     *
     * @param asset     资源（状态字段应为变更后的值）
     * @param version   版本号
     * @param readme    README 正文，随事件下发使消费端无需回查
     * @param opVersion 本次操作的版本号，单调递增
     */
    void appendAssetUploaded(AssetEntity asset, String version, String readme, long opVersion);
}
