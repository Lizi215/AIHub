package com.aihub.search.vo;

import java.util.List;
import java.util.Map;

/**
 * 搜索命中的一条结果。
 *
 * @param id         文档 id（业务对象 id）
 * @param score      相关度得分；非相关度排序时为 null
 * @param source     文档字段（{@code _source}）
 * @param highlights 高亮片段，key 为字段名，value 为含 {@code <em>} 标记的片段
 */
public record SearchHit(
        String id,
        Double score,
        Map<String, Object> source,
        Map<String, List<String>> highlights) {
}
