package com.aihub.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.List;

/**
 * Elasticsearch 客户端装配（官方 elasticsearch-java 9.x）。
 *
 * <p>不用 Spring Data Elasticsearch：Boot 3.5 只管理到 ES 8.x 客户端，而服务端是 9.5.2，
 * 8.x 客户端对 9.x 服务端不受支持。这里显式用与服务端同版本的官方客户端，
 * 代价是自己装配 transport（即本类）。
 *
 * <p>{@code ElasticsearchTransport} 必须暴露成 Bean：它同时被 {@code IndexManager}
 * 读取 jsonpMapper 与客户端共享，做成局部变量会在运行期报找不到 Bean。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class EsClientConfig {

    private static final Logger log = LoggerFactory.getLogger(EsClientConfig.class);

    private final ElasticsearchProperties properties;

    public EsClientConfig(ElasticsearchProperties properties) {
        this.properties = properties;
    }

    @Bean(destroyMethod = "close")
    public Rest5Client rest5Client() {
        List<URI> uris = properties.getUris().stream().map(URI::create).toList();
        // 超时显式设置，默认值无法按环境调整
        Rest5ClientBuilder builder = Rest5Client.builder(uris.toArray(new URI[0]))
                .setRequestConfigCallback(requestConfig -> requestConfig
                        .setConnectTimeout(Timeout.of(properties.getConnectTimeout()))
                        .setResponseTimeout(Timeout.of(properties.getSocketTimeout())));
        log.info("elasticsearch rest5 client configured, uris={}", uris);
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(Rest5Client rest5Client) {
        // 用 Jackson 作为 JSONP 映射实现，避免再引入一套 JSON 解析
        JsonpMapper mapper = new JacksonJsonpMapper();
        return new Rest5ClientTransport(rest5Client, mapper);
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
