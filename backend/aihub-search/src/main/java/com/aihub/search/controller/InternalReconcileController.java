package com.aihub.search.controller;

import com.aihub.common.result.Result;
import com.aihub.search.reconcile.IndexReconcileJob;
import com.aihub.search.vo.ReconcileReport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对账的内部触发接口。
 *
 * <p>定时任务已经会跑对账，这里额外暴露手动触发入口：索引被误删后立即重建，不必等下一个周期；
 * 验收时也能按需触发一次并看到报告。
 *
 * <p>路径在 {@code /internal/v1} 下，Gateway 不配路由，不暴露到公网。
 */
@RestController
public class InternalReconcileController {

    private final IndexReconcileJob reconcileJob;

    public InternalReconcileController(IndexReconcileJob reconcileJob) {
        this.reconcileJob = reconcileJob;
    }

    /**
     * 触发一次对账。
     *
     * @param updatedAfter ISO-8601 增量下界；不传表示全量对账（索引重建时用）
     */
    @PostMapping("/internal/v1/search/reconcile")
    public Result<ReconcileReport> reconcile(
            @RequestParam(value = "updatedAfter", required = false) String updatedAfter) {
        return Result.ok(reconcileJob.reconcile(updatedAfter));
    }
}
