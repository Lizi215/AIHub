package com.aihub.search.reconcile;

import com.aihub.api.asset.AssetIndexSourceApi;
import com.aihub.api.dto.AssetIndexDoc;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.search.index.IndexDocuments;
import com.aihub.search.index.SearchDocType;
import org.springframework.stereotype.Component;

/**
 * 资源域对账数据源：经 {@link AssetIndexSourceApi} 取数，不直接读 asset 表。
 */
@Component
public class AssetReconcileSource implements ReconcileSource {

    private final AssetIndexSourceApi assetIndexSourceApi;

    public AssetReconcileSource(AssetIndexSourceApi assetIndexSourceApi) {
        this.assetIndexSourceApi = assetIndexSourceApi;
    }

    @Override
    public SearchDocType docType() {
        return SearchDocType.ASSET;
    }

    @Override
    public PageResult<IndexDocPayload> fetchIndexable(String updatedAfter, int page, int size) {
        Result<PageResult<AssetIndexDoc>> result = assetIndexSourceApi.scanPublished(updatedAfter, page, size);
        PageResult<AssetIndexDoc> payload = unwrap(result, "scan published assets");
        return PageResult.of(payload.records().stream()
                        .map(doc -> new IndexDocPayload(doc.id(), doc.opVersion(), IndexDocuments.asset(doc)))
                        .toList(),
                payload.total(), payload.page(), payload.size());
    }

    @Override
    public PageResult<String> fetchRemoved(String updatedAfter, int page, int size) {
        return unwrap(assetIndexSourceApi.scanRemoved(updatedAfter, page, size), "scan removed assets");
    }

    private <T> T unwrap(Result<T> result, String action) {
        if (result == null || result.code() != com.aihub.common.result.ErrorCode.SUCCESS.getCode()) {
            // 对账失败必须暴露：静默跳过等于看起来对完账了，其实压根没扫
            throw new IllegalStateException(action + " failed: "
                    + (result == null ? "empty response" : result.message()));
        }
        return result.data();
    }
}
