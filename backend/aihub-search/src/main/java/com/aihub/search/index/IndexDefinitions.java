package com.aihub.search.index;

import org.springframework.stereotype.Component;

/**
 * 索引的 settings / mappings / aliases 定义。
 *
 * <p>写成 JSON 文本块而非类型化 builder：mapping 是声明式配置，builder 会变成几十行嵌套
 * lambda，反而看不出结构，JSON 也能直接对照官方文档与 {@code GET /索引/_mapping} 输出。
 *
 * <p>中文分词本应用 IK，但本机 ES 未安装该插件，先用内置 {@code cjk} 分析器（Lucene CJK bigram）：
 * 免插件、任何环境都能跑。装上 IK 后把 analyzer 换成 {@code ik_max_word} / {@code ik_smart}
 * 并重建索引即可（切别名无停机）。
 */
@Component
public class IndexDefinitions {

    /** ES 内置 CJK 分析器（bigram），IK 未安装时的替代。 */
    public static final String CJK_ANALYZER = "cjk";

    /** 索引级 settings，三个索引共用。 */
    private static final String COMMON_SETTINGS = """
            "settings": {
              "number_of_shards": 1,
              "number_of_replicas": 0
            }""";

    /**
     * 生成建索引请求体（settings + mappings + aliases）。
     *
     * <p>重建时的映射变更流程：建新索引 → 灌数据 → 原子切别名 → 删旧索引。
     */
    public String createBody(SearchDocType docType) {
        return switch (docType) {
            case ASSET -> body(docType, ASSET_PROPERTIES);
            case POST -> body(docType, POST_PROPERTIES);
            case ISSUE -> body(docType, ISSUE_PROPERTIES);
        };
    }

    private String body(SearchDocType docType, String properties) {
        return """
                {
                %s,
                  "mappings": {
                    "properties": {
                %s
                    }
                  },
                  "aliases": {
                    "%s": {},
                    "%s": {}
                  }
                }
                """.formatted(COMMON_SETTINGS, properties, docType.writeAlias(), docType.readAlias());
    }

    /** Asset 索引字段。 */
    private static final String ASSET_PROPERTIES = """
                      "id":          { "type": "keyword" },
                      "title":       { "type": "text", "analyzer": "cjk",
                                       "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } },
                      "description": { "type": "text", "analyzer": "cjk" },
                      "readme":      { "type": "text", "analyzer": "cjk" },
                      "tags":        { "type": "keyword" },
                      "category":    { "type": "keyword" },
                      "authorId":    { "type": "keyword" },
                      "status":      { "type": "keyword" },
                      "opVersion":   { "type": "long" },
                      "downloadCount": { "type": "long" },
                      "latestVersion": { "type": "keyword" },
                      "createdAt":   { "type": "date" },
                      "updatedAt":   { "type": "date" }
                """;

    /** Post 索引字段。 */
    private static final String POST_PROPERTIES = """
                      "id":       { "type": "keyword" },
                      "type":     { "type": "keyword" },
                      "title":    { "type": "text", "analyzer": "cjk",
                                    "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } },
                      "content":  { "type": "text", "analyzer": "cjk" },
                      "authorId": { "type": "keyword" },
                      "assetId":  { "type": "keyword" },
                      "opVersion": { "type": "long" },
                      "createdAt": { "type": "date" },
                      "updatedAt": { "type": "date" }
                """;

    /**
     * Issue 索引字段。
     *
     * <p>不含 {@code likeCount} / {@code commentCount}：这两类计数持续变化，点赞也不产生事件，
     * 放进索引只会展示过期数据，需要时由前端查详情。
     */
    private static final String ISSUE_PROPERTIES = """
                      "id":       { "type": "keyword" },
                      "assetId":  { "type": "keyword" },
                      "version":  { "type": "keyword" },
                      "title":    { "type": "text", "analyzer": "cjk",
                                    "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } },
                      "content":  { "type": "text", "analyzer": "cjk" },
                      "status":   { "type": "keyword" },
                      "authorId": { "type": "keyword" },
                      "opVersion": { "type": "long" },
                      "createdAt": { "type": "date" },
                      "updatedAt": { "type": "date" }
                """;
}
