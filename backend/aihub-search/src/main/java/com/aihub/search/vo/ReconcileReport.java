package com.aihub.search.vo;

import java.util.Map;

/**
 * 对账结果，给运维/验收看，不面向普通用户。
 *
 * @param updatedAfter 本次使用的增量下界；null 表示全量
 * @param perType      按 doc_type 的明细
 */
public record ReconcileReport(String updatedAfter, Map<String, TypeReport> perType) {

    /**
     * @param scanned   扫描到的记录数
     * @param upserted  实际写入索引的条数（索引缺失或版本更旧的）
     * @param rejected  被 ES 外部版本号拒绝的条数，通常是删除墓碑或乱序。
     *                  必须与 upserted 分开：IndexManager.upsert 对 409 返回 false，
     *                  不区分会把写入被拒也算成成功，让报告说谎
     * @param skipped   索引已是最新的条数
     * @param deleted   从索引删除的条数
     */
    public record TypeReport(int scanned, int upserted, int rejected, int skipped, int deleted) {
    }
}
