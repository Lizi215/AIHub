package com.aihub.search.reconcile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 对账的定时触发。
 *
 * <p>定时而非只在启动时跑一次：事件缺口可能发生在两次发布之间（消费者重启、索引被误删），
 * 只在启动时对账意味着缺口要等下次重启才补上。
 *
 * <p>增量窗口默认回看 24 小时而不是"上次执行时间"：窗口重叠成本很低（对账幂等），
 * 而一旦进程重启丢掉上次时间，缺口就会出现。宁可重复扫描，不可漏扫。
 */
@Component
public class ReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);

    private final IndexReconcileJob reconcileJob;

    /** 增量回看窗口 */
    private final Duration lookback;

    /** 是否启用定时对账，默认开，本地调试可关掉以免刷日志 */
    private final boolean enabled;

    public ReconcileScheduler(IndexReconcileJob reconcileJob,
                              @Value("${aihub.search.reconcile.lookback:24h}") Duration lookback,
                              @Value("${aihub.search.reconcile.enabled:true}") boolean enabled) {
        this.reconcileJob = reconcileJob;
        this.lookback = lookback;
        this.enabled = enabled;
    }

    /**
     * 定时对账。
     *
     * <p>{@code fixedDelay} 且初始延迟 5 分钟：避开启动瞬间（业务服务可能还没注册好），
     * 也保证上一轮没跑完不会开始下一轮。
     */
    @Scheduled(initialDelayString = "${aihub.search.reconcile.initial-delay:5m}",
            fixedDelayString = "${aihub.search.reconcile.interval:1h}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        String updatedAfter = LocalDateTime.now().minus(lookback)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        try {
            reconcileJob.reconcile(updatedAfter);
        } catch (Exception e) {
            // 定时任务抛异常会导致后续调度中断，必须自己兜住
            log.error("scheduled index reconcile failed", e);
        }
    }
}
