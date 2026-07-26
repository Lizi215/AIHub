package com.aihub.asset.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * outbox 中继的定时触发。
 *
 * <p>写入路径是「提交后立即尝试投递，失败留给定时任务重投」（docs/SEARCH.md §1）。立即投递让
 * 绝大多数事件秒级送达，定时兜底保证已落库的事件最终一定被投递——没有兜底，一次 MQ 抖动就会永久丢事件。
 *
 * <p>间隔走配置：太密会空转刷日志，太疏会让故障恢复变慢。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aihub.asset.outbox", name = "enabled", havingValue = "true")
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    /**
     * 定时重投。
     *
     * <p>用 {@code fixedDelay} 而非 {@code fixedRate}：上一轮没跑完就不开始下一轮，
     * 否则积压时会并发跑多轮、重复投递。
     */
    @Scheduled(fixedDelayString = "${aihub.asset.outbox.poll-interval:5s}")
    public void poll() {
        try {
            int sent = relay.relayOnce();
            if (sent > 0) {
                log.info("outbox relay delivered {} event(s)", sent);
            }
        } catch (Exception e) {
            // 定时任务里抛异常会终止后续调度（取决于实现），必须自己吞掉
            log.error("outbox relay round failed", e);
        }
    }
}
