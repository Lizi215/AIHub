package com.aihub.common.event;

/**
 * 事件消费的版本与幂等判定（纯逻辑，便于单测）。
 *
 * <p>MQ 只保证「至少一次」、不保证顺序：同一条资源被连续修改时，若旧事件后到并被直接应用，
 * 索引就会回退到过期状态，这类问题线上极难复现。
 */
public final class EventVersionPolicy {

    private EventVersionPolicy() {
    }

    /**
     * 判断携带 {@code incomingOpVersion} 的事件是否应当生效：必须严格大于已应用版本。
     *
     * <p>相等为重复投递，更小为乱序的旧事件（否则新数据被覆盖），已应用版本为空视为首次处理，
     * 来者本身为空则无法判定新旧，按不生效处理（失败关闭）。
     */
    public static boolean shouldApply(Long appliedOpVersion, Long incomingOpVersion) {
        if (incomingOpVersion == null) {
            return false;
        }
        if (appliedOpVersion == null) {
            return true;
        }
        return incomingOpVersion > appliedOpVersion;
    }
}
