package com.aihub.search.index;

import com.aihub.api.dto.AssetIndexDoc;
import com.aihub.api.dto.IssueIndexDoc;
import com.aihub.api.dto.PostIndexDoc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把业务 DTO 转成索引文档（Map 形式）。
 *
 * <p>抽出来是为了字段名映射集中一处，改 mapping 只改这里，避免事件消费与对账写出两套字段名；
 * 也便于单测——映射写错（比如 opVersion 写成字符串）在 ES 里不会立刻报错，但防乱序会静默失效。
 */
public final class IndexDocuments {

    private IndexDocuments() {
    }

    public static Map<String, Object> asset(AssetIndexDoc doc) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", doc.id());
        document.put("title", doc.title());
        document.put("description", doc.description());
        document.put("readme", doc.readme());
        document.put("tags", doc.tags() == null ? java.util.List.of() : doc.tags());
        document.put("category", doc.category());
        document.put("authorId", doc.authorId());
        document.put("status", doc.status());
        // 必须是数字：防乱序比对做数值比较，写成字符串会静默失效
        document.put(IndexManager.FIELD_OP_VERSION, doc.opVersion());
        document.put("downloadCount", doc.downloadCount());
        document.put("latestVersion", doc.latestVersion());
        document.put("createdAt", doc.createdAt());
        document.put("updatedAt", doc.updatedAt());
        return document;
    }

    public static Map<String, Object> post(PostIndexDoc doc) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", doc.id());
        document.put("type", doc.type());
        document.put("title", doc.title());
        document.put("content", doc.content());
        document.put("authorId", doc.authorId());
        document.put("assetId", doc.assetId());
        document.put(IndexManager.FIELD_OP_VERSION, doc.opVersion());
        document.put("createdAt", doc.createdAt());
        document.put("updatedAt", doc.updatedAt());
        return document;
    }

    public static Map<String, Object> issue(IssueIndexDoc doc) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", doc.id());
        document.put("assetId", doc.assetId());
        document.put("version", doc.version());
        document.put("title", doc.title());
        document.put("content", doc.content());
        document.put("status", doc.status());
        document.put("authorId", doc.authorId());
        document.put(IndexManager.FIELD_OP_VERSION, doc.opVersion());
        document.put("createdAt", doc.createdAt());
        document.put("updatedAt", doc.updatedAt());
        return document;
    }
}
