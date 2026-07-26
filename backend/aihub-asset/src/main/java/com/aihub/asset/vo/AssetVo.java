package com.aihub.asset.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源列表项。
 */
public record AssetVo(
        Long id,
        Long ownerId,
        String ownerName,
        String name,
        String summary,
        String category,
        String status,
        String latestVersion,
        List<String> tags,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
