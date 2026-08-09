package com.aihub.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Elasticsearch 连接配置。
 *
 * <p>地址走配置，换环境只改配置不改代码。
 */
@ConfigurationProperties(prefix = "aihub.search.elasticsearch")
public class ElasticsearchProperties {

    /** ES 节点地址，多个用逗号分隔。 */
    private List<String> uris = List.of("http://127.0.0.1:9200");

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 请求超时，搜索可能较慢，但要短于网关超时 */
    private Duration socketTimeout = Duration.ofSeconds(10);

    /**
     * 启动时是否自动创建/校验索引。
     *
     * <p>默认开启：索引缺失时搜索服务没有意义，启动即失败比启动了却搜不到更容易发现。
     */
    private boolean bootstrapEnabled = true;

    /**
     * 写入后是否等待 ES 刷新（{@code wait_for}）。
     *
     * <p>默认 true：写完立即可搜，便于验收与本地演示。
     * 生产应设为 false，由 ES 按 refresh_interval 批量刷新，否则写入吞吐被刷新开销拖住。
     */
    private boolean waitForRefresh = true;

    public List<String> getUris() {
        return uris;
    }

    public void setUris(List<String> uris) {
        this.uris = uris;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(Duration socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    public boolean isBootstrapEnabled() {
        return bootstrapEnabled;
    }

    public void setBootstrapEnabled(boolean bootstrapEnabled) {
        this.bootstrapEnabled = bootstrapEnabled;
    }

    public boolean isWaitForRefresh() {
        return waitForRefresh;
    }

    public void setWaitForRefresh(boolean waitForRefresh) {
        this.waitForRefresh = waitForRefresh;
    }
}
