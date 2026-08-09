package com.aihub.search.reconcile;

import com.aihub.common.result.PageResult;
import com.aihub.search.index.IndexManager;
import com.aihub.search.index.SearchDocType;
import com.aihub.search.vo.ReconcileReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账任务：只补缺失或版本更旧的文档，并清理已不该在索引里的文档。
 *
 * <p>价值全在边界上：多做会让每次对账都全量重写把索引写爆，少做则缺口永远补不上，两侧都钉死。
 */
class IndexReconcileJobTest {

    private IndexManager indexManager;
    private ReconcileSource source;
    private IndexReconcileJob job;

    @BeforeEach
    void setUp() {
        indexManager = mock(IndexManager.class);
        source = new StubSource();
        job = new IndexReconcileJob(indexManager, List.of(source));
    }

    @Test
    @DisplayName("索引缺失（首次/被删）→ 写入；版本更旧 → 写入")
    void shouldUpsertMissingOrStaleDocuments() {
        when(indexManager.readOpVersion(SearchDocType.ASSET, "1")).thenReturn(null);
        when(indexManager.readOpVersion(SearchDocType.ASSET, "2")).thenReturn(1L);
        when(indexManager.upsert(any(), anyString(), any(), anyLong())).thenReturn(true);

        ReconcileReport report = job.reconcile(null);

        verify(indexManager).upsert(eq(SearchDocType.ASSET), eq("1"), any(), eq(2L));
        verify(indexManager).upsert(eq(SearchDocType.ASSET), eq("2"), any(), eq(2L));
        assertThat(report.perType().get("asset").upserted()).isEqualTo(2);
    }

    @Test
    @DisplayName("写入被 ES 外部版本号拒绝（如墓碑挡住同版本号重建）→ 单独计数，不算成功")
    void shouldCountRejectedWritesSeparately() {
        // 删除文档后留下的墓碑会让同版本号重建被 409 拒绝，
        // 不区分的话报告会显示 upserted=2，而索引里实际一条都没有
        when(indexManager.readOpVersion(SearchDocType.ASSET, "1")).thenReturn(null);
        when(indexManager.readOpVersion(SearchDocType.ASSET, "2")).thenReturn(null);
        when(indexManager.upsert(eq(SearchDocType.ASSET), eq("1"), any(), eq(2L))).thenReturn(true);
        when(indexManager.upsert(eq(SearchDocType.ASSET), eq("2"), any(), eq(2L))).thenReturn(false);

        ReconcileReport report = job.reconcile(null);

        ReconcileReport.TypeReport asset = report.perType().get("asset");
        assertThat(asset.upserted()).isEqualTo(1);
        assertThat(asset.rejected()).isEqualTo(1);
    }

    @Test
    @DisplayName("索引已是最新（applied >= incoming）→ 跳过，不做无谓写入")
    void shouldSkipUpToDateDocuments() {
        when(indexManager.readOpVersion(SearchDocType.ASSET, "1")).thenReturn(5L);
        when(indexManager.readOpVersion(SearchDocType.ASSET, "2")).thenReturn(2L);

        ReconcileReport report = job.reconcile(null);

        verify(indexManager, never()).upsert(any(), anyString(), any(), anyLong());
        assertThat(report.perType().get("asset").skipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("应移除的记录被删除（删除后 ES 文档同步移除）")
    void shouldDeleteRemovedDocuments() {
        when(indexManager.deleteDocument(SearchDocType.ASSET, "999")).thenReturn(true);

        ReconcileReport report = job.reconcile(null);

        verify(indexManager).deleteDocument(SearchDocType.ASSET, "999");
        assertThat(report.perType().get("asset").deleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("对账前先确保索引存在：索引被误删时才能补得回来")
    void shouldEnsureIndexesFirst() {
        job.reconcile(null);

        verify(indexManager).ensureIndexes();
    }

    /** 固定数据源：两条应索引的文档加一条应删除的记录，一次翻完。 */
    private static class StubSource implements ReconcileSource {

        @Override
        public SearchDocType docType() {
            return SearchDocType.ASSET;
        }

        @Override
        public PageResult<IndexDocPayload> fetchIndexable(String updatedAfter, int page, int size) {
            if (page > 1) {
                return PageResult.of(List.of(), 2, page, size);
            }
            return PageResult.of(List.of(
                    new IndexDocPayload("1", 2L, Map.of("id", "1")),
                    new IndexDocPayload("2", 2L, Map.of("id", "2"))), 2, page, size);
        }

        @Override
        public PageResult<String> fetchRemoved(String updatedAfter, int page, int size) {
            if (page > 1) {
                return PageResult.of(List.of(), 1, page, size);
            }
            return PageResult.of(List.of("999"), 1, page, size);
        }
    }
}
