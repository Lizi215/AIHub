package com.aihub.search.index;

/**
 * 索引中的文档类型。
 *
 * <p>代码里只允许出现别名，禁止出现带版本号的物理索引名，这是零停机重建的前提：
 * 重建时只切别名，查询与写入代码一行都不用改。
 *
 * @param physicalIndex 物理索引名，只在建索引 / 重建 / 运维脚本里出现
 * @param writeAlias    写入别名，索引写入一律走它
 * @param readAlias     读取别名，搜索一律走它
 */
public enum SearchDocType {

    ASSET("aihub_asset_v1", "aihub_asset_write", "aihub_asset_read"),
    POST("aihub_post_v1", "aihub_post_write", "aihub_post_read"),
    ISSUE("aihub_issue_v1", "aihub_issue_write", "aihub_issue_read");

    private final String physicalIndex;
    private final String writeAlias;
    private final String readAlias;

    SearchDocType(String physicalIndex, String writeAlias, String readAlias) {
        this.physicalIndex = physicalIndex;
        this.writeAlias = writeAlias;
        this.readAlias = readAlias;
    }

    public String physicalIndex() {
        return physicalIndex;
    }

    public String writeAlias() {
        return writeAlias;
    }

    public String readAlias() {
        return readAlias;
    }

    /** 事件里的 doc_type 取值，与枚举同名小写。 */
    public String code() {
        return name().toLowerCase();
    }

    public static SearchDocType ofCode(String code) {
        for (SearchDocType type : values()) {
            if (type.code().equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown search doc type: " + code);
    }
}
