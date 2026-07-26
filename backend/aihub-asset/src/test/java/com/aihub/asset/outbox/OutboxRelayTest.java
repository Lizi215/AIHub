package com.aihub.asset.outbox;

import com.aihub.asset.entity.EventOutboxEntity;
import com.aihub.asset.mapper.EventOutboxMapper;
import com.aihub.common.outbox.EventDispatcher;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * outbox 中继的候选处理、退避与状态推进。
 *
 * <p>不依赖 MQ，可在 RocketMQ 缺失时被真实验证——这是把投递抽象成 {@link EventDispatcher} 的收益。
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String EVENT_ID = "evt-0001";

    @Mock
    private EventOutboxMapper outboxMapper;

    private OutboxProperties properties;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.setBatchSize(10);
        properties.setMaxRetryCount(3);
        properties.setBaseBackoff(Duration.ofSeconds(10));
        properties.setMaxBackoff(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("投递成功标记 SENT，且不改动 retryCount")
    void shouldMarkSentOnSuccess() {
        relay = relayWith(row(1L, EVENT_ID, 0));
        relay = relayWithDispatcher(relay, message -> {
        });

        assertThat(relay.relayOnce()).isEqualTo(1);

        EventOutboxEntity update = capturedSingleUpdate();
        assertThat(update.getStatus()).isEqualTo("SENT");
        assertThat(update.getRetryCount()).isNull();
    }

    @Test
    @DisplayName("投递失败保持 PENDING、retryCount+1，并设置未来的重试时间（退避）")
    void shouldBackoffOnFailure() {
        relay = relayWithDispatcher(relayWith(row(1L, EVENT_ID, 0)), message -> {
            throw new IllegalStateException("mq down");
        });

        assertThat(relay.relayOnce()).isZero();

        EventOutboxEntity update = capturedSingleUpdate();
        assertThat(update.getStatus()).isEqualTo("PENDING");
        assertThat(update.getRetryCount()).isEqualTo(1);
        assertThat(update.getNextRetryAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("退避按指数增长：retry 越大等待越久")
    void shouldIncreaseBackoffExponentially() {
        // 抬高上限，否则 retryCount=2 的那条会直接转 FAILED 而拿不到 nextRetryAt
        properties.setMaxRetryCount(10);
        LocalDateTime before = LocalDateTime.now();

        relay = relayWithDispatcher(relayWith(row(1L, EVENT_ID, 0)), failAlways());
        relay.relayOnce();
        LocalDateTime firstRetryAt = capturedSingleUpdate().getNextRetryAt();

        // 清掉上一轮的调用记录（保留 stub），否则下面「期望恰好一次」的断言会看到两次
        org.mockito.Mockito.clearInvocations(outboxMapper);

        relay = relayWithDispatcher(relayWith(row(1L, EVENT_ID, 2)), failAlways());
        relay.relayOnce();
        LocalDateTime thirdRetryAt = capturedSingleUpdate().getNextRetryAt();

        // 第 1 次退避 10s，第 3 次退避 10 * 2^2 = 40s
        assertThat(Duration.between(before, firstRetryAt)).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(11));
        assertThat(Duration.between(before, thirdRetryAt)).isBetween(Duration.ofSeconds(39), Duration.ofSeconds(41));
    }

    @Test
    @DisplayName("重试达到上限转 FAILED 并停止重试，且不再设置重试时间")
    void shouldMarkFailedWhenRetriesExhausted() {
        // maxRetryCount=3，当前已是第 3 次重试
        relay = relayWithDispatcher(relayWith(row(1L, EVENT_ID, 2)), failAlways());

        relay.relayOnce();

        EventOutboxEntity update = capturedSingleUpdate();
        assertThat(update.getStatus()).isEqualTo("FAILED");
        assertThat(update.getRetryCount()).isEqualTo(3);
        assertThat(update.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("单条失败不阻断同批其它记录：一条坏消息不能卡死整个队列")
    void shouldContinueWithOtherRecordsWhenOneFails() {
        EventOutboxEntity bad = row(1L, "evt-bad", 0);
        EventOutboxEntity good = row(2L, "evt-good", 0);
        relay = relayWith(bad, good);
        relay = relayWithDispatcher(relay, message -> {
            if (message.eventId().equals("evt-bad")) {
                throw new IllegalStateException("boom");
            }
        });

        assertThat(relay.relayOnce()).isEqualTo(1);

        ArgumentCaptor<EventOutboxEntity> captor = ArgumentCaptor.forClass(EventOutboxEntity.class);
        verify(outboxMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues()).extracting(EventOutboxEntity::getStatus)
                .containsExactlyInAnyOrder("PENDING", "SENT");
    }

    @Test
    @DisplayName("没有候选记录时直接返回 0，不调用投递")
    void shouldDoNothingWhenNoCandidate() throws Exception {
        relay = relayWith();
        EventDispatcher dispatcher = org.mockito.Mockito.mock(EventDispatcher.class);
        relay = new OutboxRelay(outboxMapper, dispatcher, properties);

        assertThat(relay.relayOnce()).isZero();

        // dispatch 声明了 throws Exception，因此本方法需要声明异常
        verify(dispatcher, never()).dispatch(any());
    }

    /* ------------------------------------------------------------ 测试辅助 */

    private OutboxRelay relayWith(EventOutboxEntity... rows) {
        Page<EventOutboxEntity> page = new Page<>(1, properties.getBatchSize(), false);
        page.setRecords(List.of(rows));
        when(outboxMapper.selectPage(any(), any())).thenReturn(page);
        return new OutboxRelay(outboxMapper, message -> {
        }, properties);
    }

    private OutboxRelay relayWithDispatcher(OutboxRelay ignored, EventDispatcher dispatcher) {
        return new OutboxRelay(outboxMapper, dispatcher, properties);
    }

    private EventDispatcher failAlways() {
        return message -> {
            throw new IllegalStateException("mq down");
        };
    }

    private EventOutboxEntity capturedSingleUpdate() {
        ArgumentCaptor<EventOutboxEntity> captor = ArgumentCaptor.forClass(EventOutboxEntity.class);
        verify(outboxMapper).updateById(captor.capture());
        return captor.getValue();
    }

    private EventOutboxEntity row(Long id, String eventId, int retryCount) {
        EventOutboxEntity row = new EventOutboxEntity();
        row.setId(id);
        row.setEventId(eventId);
        row.setTopic("aihub-asset-event");
        row.setTag("ASSET_UPLOADED");
        row.setPayload("{}");
        row.setStatus("PENDING");
        row.setRetryCount(retryCount);
        row.setDeleted(0L);
        return row;
    }
}
