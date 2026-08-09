package com.aihub.search.reconcile;

import com.aihub.api.community.CommunityIndexSourceApi;
import com.aihub.api.dto.IssueIndexDoc;
import com.aihub.api.dto.PostIndexDoc;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.search.index.IndexDocuments;
import com.aihub.search.index.SearchDocType;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 社区域对账数据源：帖子与 Issue 各一个实例，由 {@link SearchDocType} 决定。
 *
 * <p>两种文档的出数接口只差取哪个方法，用一个类参数化，避免复制两份几乎相同的代码。
 */
public class CommunityReconcileSource implements ReconcileSource {

    private final SearchDocType docType;
    private final CommunityIndexSourceApi api;
    private final Function<PostIndexDoc, IndexDocPayload> postMapper = doc ->
            new IndexDocPayload(doc.id(), doc.opVersion(), IndexDocuments.post(doc));
    private final Function<IssueIndexDoc, IndexDocPayload> issueMapper = doc ->
            new IndexDocPayload(doc.id(), doc.opVersion(), IndexDocuments.issue(doc));

    /** Spring 需要无歧义的构造，两个实例在配置类里显式声明 */
    public CommunityReconcileSource(CommunityIndexSourceApi api, SearchDocType docType) {
        this.api = api;
        this.docType = docType;
    }

    /** 帖子数据源 */
    public static CommunityReconcileSource posts(CommunityIndexSourceApi api) {
        return new CommunityReconcileSource(api, SearchDocType.POST);
    }

    /** Issue 数据源 */
    public static CommunityReconcileSource issues(CommunityIndexSourceApi api) {
        return new CommunityReconcileSource(api, SearchDocType.ISSUE);
    }

    @Override
    public SearchDocType docType() {
        return docType;
    }

    @Override
    public PageResult<IndexDocPayload> fetchIndexable(String updatedAfter, int page, int size) {
        if (docType == SearchDocType.POST) {
            return map(unwrap(api.scanPosts(updatedAfter, page, size), "scan posts"), postMapper);
        }
        return map(unwrap(api.scanIssues(updatedAfter, page, size), "scan issues"), issueMapper);
    }

    @Override
    public PageResult<String> fetchRemoved(String updatedAfter, int page, int size) {
        Supplier<Result<PageResult<String>>> call = docType == SearchDocType.POST
                ? () -> api.scanRemovedPosts(updatedAfter, page, size)
                : () -> api.scanRemovedIssues(updatedAfter, page, size);
        return unwrap(call.get(), "scan removed " + docType.code());
    }

    private <T> PageResult<IndexDocPayload> map(PageResult<T> source, Function<T, IndexDocPayload> mapper) {
        return PageResult.of(source.records().stream().map(mapper).toList(),
                source.total(), source.page(), source.size());
    }

    private <T> T unwrap(Result<T> result, String action) {
        if (result == null || result.code() != ErrorCode.SUCCESS.getCode()) {
            throw new IllegalStateException(action + " failed: "
                    + (result == null ? "empty response" : result.message()));
        }
        return result.data();
    }
}
