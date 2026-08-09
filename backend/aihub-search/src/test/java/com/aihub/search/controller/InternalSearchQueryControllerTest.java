package com.aihub.search.controller;

import com.aihub.api.dto.AssetQueryItem;
import com.aihub.common.result.PageResult;
import com.aihub.search.service.SearchService;
import com.aihub.search.vo.SearchHit;
import com.aihub.search.vo.SearchPageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内部查询端点：把 ES 的 {@code SearchHit}（Map 形态的文档）翻译成服务间 DTO。
 *
 * <p>重点在字段映射的健壮性：Agent 拿到的是这个 DTO，任何解析失误都会变成它上下文里的脏数据。
 */
@ExtendWith(MockitoExtension.class)
class InternalSearchQueryControllerTest {

    @Mock
    private SearchService searchService;

    @InjectMocks
    private InternalSearchQueryController controller;

    @Test
    @DisplayName("ES 文档字段映射到服务间 DTO：title→name、description→summary，id 转成 Long")
    void shouldMapEsDocumentToQueryItem() {
        SearchHit hit = new SearchHit("1234567890123456789", 1.7d,
                Map.of("id", "1234567890123456789",
                        "title", "spring cloud skill",
                        "description", "为 Spring Cloud 项目提供配置模板",
                        "category", "SKILL",
                        "status", "PUBLISHED",
                        "authorId", "7",
                        "latestVersion", "1.0.0"),
                null);
        when(searchService.searchAssets(eq("spring"), isNull(), isNull(), isNull(), eq(1), eq(5), isNull(), isNull()))
                .thenReturn(new SearchPageResult<>(List.of(hit), 1, 1, 5, null));

        PageResult<AssetQueryItem> result = controller.searchAssets("spring", null, null, 1, 5).data();

        assertThat(result.records()).hasSize(1);
        AssetQueryItem item = result.records().get(0);
        assertThat(item.id()).isEqualTo(1234567890123456789L);
        assertThat(item.name()).isEqualTo("spring cloud skill");
        assertThat(item.summary()).isEqualTo("为 Spring Cloud 项目提供配置模板");
        assertThat(item.category()).isEqualTo("SKILL");
        assertThat(item.status()).isEqualTo("PUBLISHED");
        assertThat(item.ownerId()).isEqualTo(7L);
        assertThat(item.latestVersion()).isEqualTo("1.0.0");

        // 必须走相关度排序（sort 不传），不带 search_after 游标也不带 ownerId，Agent 每次都是新检索
        verify(searchService).searchAssets(eq("spring"), isNull(), isNull(), isNull(), eq(1), eq(5), isNull(), isNull());
    }

    @Test
    @DisplayName("文档缺 id（坏数据）→ 跳过该条，不返回无法回查的候选；缺失字段按 null 处理")
    void shouldSkipDocumentWithoutId() {
        // ES 的 _source 里没写进文档的字段就是没有这个 key，而不是值为 null
        SearchHit bad = new SearchHit(null, 1.0d, Map.of("title", "无 id 的脏文档"), null);
        SearchHit good = new SearchHit("42", 0.9d,
                Map.of("id", "42", "title", "正常的",
                        "category", "OTHER", "status", "PUBLISHED", "authorId", "7"),
                null);
        when(searchService.searchAssets(any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new SearchPageResult<>(List.of(bad, good), 2, 1, 5, null));

        PageResult<AssetQueryItem> result = controller.searchAssets(null, null, null, 1, 5).data();

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).id()).isEqualTo(42L);
        // 字段缺失显式为 null 而不是吞成空串，调用方据此判断要不要展示
        assertThat(result.records().get(0).summary()).isNull();
        assertThat(result.records().get(0).latestVersion()).isNull();
    }
}
