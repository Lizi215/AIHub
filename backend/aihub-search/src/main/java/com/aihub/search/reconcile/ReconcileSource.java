package com.aihub.search.reconcile;

import com.aihub.common.result.PageResult;

import java.util.Map;

/**
 * 对账数据源：某个 doc_type 应以什么为准。
 *
 * <p>每个 doc_type 一个实现（asset / post / issue）。抽象出接口而不是在一个大方法里写
 * switch-case：加一种文档类型只是新增实现，不用回头改对账主流程，避免牵动已验证的逻辑。
 */
public interface ReconcileSource {

    com.aihub.search.index.SearchDocType docType();

    /**
     * 拉取应当存在于索引中的文档（分页）。
     *
     * @param updatedAfter ISO-8601 增量下界；为空表示全量
     */
    PageResult<IndexDocPayload> fetchIndexable(String updatedAfter, int page, int size);

    /**
     * 拉取应当从索引中移除的文档 id（分页）。
     */
    PageResult<String> fetchRemoved(String updatedAfter, int page, int size);

    /**
     * 对账载荷：索引文档加用于比对版本的 opVersion。
     *
     * @param document 与 mapping 对应的字段，由各 Source 自己组装
     */
    record IndexDocPayload(String id, long opVersion, Map<String, Object> document) {
    }
}
