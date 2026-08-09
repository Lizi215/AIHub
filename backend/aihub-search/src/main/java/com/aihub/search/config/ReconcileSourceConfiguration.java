package com.aihub.search.config;

import com.aihub.api.community.CommunityIndexSourceApi;
import com.aihub.search.reconcile.CommunityReconcileSource;
import com.aihub.search.reconcile.ReconcileSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 社区域对账数据源的装配。
 *
 * <p>{@link CommunityReconcileSource} 需要"帖子还是 Issue"这个参数，无法靠组件扫描装配，
 * 在这里显式声明两个 Bean。
 */
@Configuration(proxyBeanMethods = false)
public class ReconcileSourceConfiguration {

    @Bean
    public ReconcileSource postReconcileSource(CommunityIndexSourceApi api) {
        return CommunityReconcileSource.posts(api);
    }

    @Bean
    public ReconcileSource issueReconcileSource(CommunityIndexSourceApi api) {
        return CommunityReconcileSource.issues(api);
    }
}
