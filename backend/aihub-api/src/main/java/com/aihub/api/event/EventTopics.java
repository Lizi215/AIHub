package com.aihub.api.event;

/**
 * 事件 topic / tag / 类型常量。
 *
 * <p>放在 `aihub-api` 是因为生产者与消费者分属不同服务，必须共用同一份契约，
 * 各写一份字符串字面量迟早会漂移成「生产了但没人消费」。
 */
public final class EventTopics {

    private EventTopics() {
    }

    /** 资源域事件 topic */
    public static final String ASSET_TOPIC = "aihub-asset-event";

    /** 资源发布（含新增版本）事件 */
    public static final String ASSET_UPLOADED = "ASSET_UPLOADED";

    /** 社区域事件 topic */
    public static final String COMMUNITY_TOPIC = "aihub-community-event";

    /** 帖子创建事件（docs/MESSAGING.md §1） */
    public static final String POST_CREATED = "POST_CREATED";

    /**
     * 下载事件 topic / tag（docs/ARCHITECTURE.md §8.2）。
     *
     * <p>该事件不走 outbox：docs/SEARCH.md §1 明确 File/下载链路不持有业务表，
     * 下载统计属非关键操作，允许极少量事件丢失，直接投递即可。
     */
    public static final String DOWNLOAD_TOPIC = "aihub-download-event";

    /** 下载完成事件 */
    public static final String DOWNLOAD_COMPLETED = "DOWNLOAD_COMPLETED";

    /** 当前事件结构版本，随结构演进递增，消费端据此兼容旧消息 */
    public static final int CURRENT_EVENT_VERSION = 1;
}
