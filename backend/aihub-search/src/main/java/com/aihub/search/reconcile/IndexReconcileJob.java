package com.aihub.search.reconcile;

import com.aihub.search.index.IndexManager;
import com.aihub.search.index.SearchDocType;
import com.aihub.search.vo.ReconcileReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账任务，事件驱动之外的最后一道兜底。
 *
 * <p>以 MySQL 为准，按 {@code updated_at} 增量比对记录在 ES 中是否存在且版本一致，补齐差异，
 * 并删掉已不该在索引里的文档。
 *
 * <p>必须有它：事件驱动是最终一致，消息可能因 producer 崩溃、消费失败进死信、索引被误删而丢失，
 * 没有对账这些缺口会永久存在且没有任何报错。
 *
 * <p>幂等：只做补齐/删除，写入前比对 opVersion，反复跑不会产生重复或回退。
 */
@Component
public class IndexReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(IndexReconcileJob.class);

    /** 每页拉取条数，太小请求次数爆炸，太大单次对账的内存与超时风险上升 */
    private static final int PAGE_SIZE = 200;

    /** 单次对账最多翻页数，防止业务数据异常增长时无限翻页 */
    private static final int MAX_PAGES = 200;

    private final IndexManager indexManager;
    private final List<ReconcileSource> sources;

    public IndexReconcileJob(IndexManager indexManager, List<ReconcileSource> sources) {
        this.indexManager = indexManager;
        this.sources = sources;
    }

    /**
     * 执行一轮对账。
     *
     * @param updatedAfter ISO-8601 增量下界；为空表示全量（索引被删后重建走这条）
     */
    public ReconcileReport reconcile(String updatedAfter) {
        // 索引可能被误删，对账前先确保存在，否则补齐会全部失败
        indexManager.ensureIndexes();

        Map<String, ReconcileReport.TypeReport> perType = new LinkedHashMap<>();
        for (ReconcileSource source : sources) {
            perType.put(source.docType().code(), reconcileOne(source, updatedAfter));
        }
        ReconcileReport report = new ReconcileReport(updatedAfter, perType);
        log.info("index reconcile finished, updatedAfter={}, report={}", updatedAfter, perType);
        return report;
    }

    private ReconcileReport.TypeReport reconcileOne(ReconcileSource source, String updatedAfter) {
        SearchDocType docType = source.docType();
        int scanned = 0;
        int upserted = 0;
        int rejected = 0;
        int skipped = 0;
        int deleted = 0;

        // ① 补齐 / 更新：只处理索引里没有或版本更旧的文档
        for (int page = 1; page <= MAX_PAGES; page++) {
            var result = source.fetchIndexable(updatedAfter, page, PAGE_SIZE);
            if (result.records().isEmpty()) {
                break;
            }
            for (ReconcileSource.IndexDocPayload payload : result.records()) {
                scanned++;
                Long applied = indexManager.readOpVersion(docType, payload.id());
                if (applied != null && applied >= payload.opVersion()) {
                    skipped++;
                    continue;
                }
                // upsert 返回 false 表示被 ES 外部版本号拒绝（如删除墓碑挡住同版本号重建），
                // 必须与成功分开统计，否则报告会把写入被拒也算成补齐，实际索引里没有
                if (indexManager.upsert(docType, payload.id(), payload.document(), payload.opVersion())) {
                    upserted++;
                } else {
                    rejected++;
                }
            }
            if (result.records().size() < PAGE_SIZE) {
                break;
            }
        }

        // ② 删除：MySQL 里已不该进索引的记录，索引里若有就删掉
        for (int page = 1; page <= MAX_PAGES; page++) {
            var removed = source.fetchRemoved(updatedAfter, page, PAGE_SIZE);
            if (removed.records().isEmpty()) {
                break;
            }
            for (String id : removed.records()) {
                // 只统计真正删掉的，本来就不在索引里时不算删除
                if (indexManager.deleteDocument(docType, id)) {
                    deleted++;
                }
            }
            if (removed.records().size() < PAGE_SIZE) {
                break;
            }
        }
        return new ReconcileReport.TypeReport(scanned, upserted, rejected, skipped, deleted);
    }
}
