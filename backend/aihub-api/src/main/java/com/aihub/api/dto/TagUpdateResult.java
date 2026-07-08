package com.aihub.api.dto;

import java.util.List;

/**
 * 标签写入结果：落在资源上的最终标签列表（合并/替换之后）。
 */
public record TagUpdateResult(Long assetId, String assetName, List<String> tags) {
}
