package com.aihub.search.index;

import com.aihub.search.config.ElasticsearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时创建缺失的索引（mapping + 读写别名）。
 *
 * <p>放在启动阶段而不是定时任务：索引不存在时搜索服务没有意义，早失败比服务起来了再全 500 好定位。
 * 对账任务同样会先调 {@link IndexManager#ensureIndexes()}，索引被误删后仍能自愈。
 *
 * <p>失败即启动失败，除非显式关掉 {@code aihub.search.elasticsearch.bootstrap-enabled}。
 */
@Component
public class IndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexBootstrap.class);

    private final IndexManager indexManager;
    private final ElasticsearchProperties properties;

    public IndexBootstrap(IndexManager indexManager, ElasticsearchProperties properties) {
        this.indexManager = indexManager;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isBootstrapEnabled()) {
            log.warn("search index bootstrap disabled (aihub.search.elasticsearch.bootstrap-enabled=false)");
            return;
        }
        indexManager.ensureIndexes();
        for (SearchDocType docType : SearchDocType.values()) {
            log.info("search index ready, index={}, documents={}",
                    docType.physicalIndex(), indexManager.count(docType));
        }
    }
}
