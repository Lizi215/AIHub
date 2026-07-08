package com.aihub.common.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * opVersion 防乱序：旧事件不得覆盖新文档。
 */
class EventVersionPolicyTest {

    @Test
    @DisplayName("首次处理（没有已应用版本）应生效")
    void shouldApplyWhenNothingAppliedYet() {
        assertThat(EventVersionPolicy.shouldApply(null, 1L)).isTrue();
    }

    @Test
    @DisplayName("新版本更大应生效")
    void shouldApplyNewerVersion() {
        assertThat(EventVersionPolicy.shouldApply(5L, 6L)).isTrue();
    }

    @Test
    @DisplayName("版本相同视为重复投递，跳过")
    void shouldSkipSameVersion() {
        assertThat(EventVersionPolicy.shouldApply(5L, 5L)).isFalse();
    }

    @Test
    @DisplayName("旧事件后到必须跳过，否则新文档会被过期数据覆盖")
    void shouldSkipStaleVersion() {
        assertThat(EventVersionPolicy.shouldApply(5L, 4L)).isFalse();
        assertThat(EventVersionPolicy.shouldApply(5L, 1L)).isFalse();
    }

    @Test
    @DisplayName("事件未携带 opVersion 时无法判定新旧，失败关闭")
    void shouldFailClosedWhenIncomingVersionMissing() {
        assertThat(EventVersionPolicy.shouldApply(5L, null)).isFalse();
        assertThat(EventVersionPolicy.shouldApply(null, null)).isFalse();
    }
}
