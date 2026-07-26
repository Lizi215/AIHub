package com.aihub.asset.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * outbox 中继配置。
 */
@ConfigurationProperties(prefix = "aihub.asset.outbox")
public class OutboxProperties {

    /**
     * 是否启用中继。默认关闭：没有 MQ 实现时，打开却没有可用的 {@link EventDispatcher}
     * 应当启动失败，而不是静默空转。
     */
    private boolean enabled = false;

    /** 单轮最多处理多少条 */
    private int batchSize = 100;

    /** 超过该重试次数标记为 FAILED，不再重试 */
    private int maxRetryCount = 5;

    /** 退避基数，实际等待 = base * 2^(retry-1)，并以 maxBackoff 封顶 */
    private Duration baseBackoff = Duration.ofSeconds(10);

    private Duration maxBackoff = Duration.ofMinutes(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public Duration getBaseBackoff() {
        return baseBackoff;
    }

    public void setBaseBackoff(Duration baseBackoff) {
        this.baseBackoff = baseBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }
}
