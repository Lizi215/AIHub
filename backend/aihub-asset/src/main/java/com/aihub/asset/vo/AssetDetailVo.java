package com.aihub.asset.vo;

import java.time.LocalDateTime;

/**
 * 资源详情。README 只在详情返回，列表不带，避免列表接口变胖。
 */
public record AssetDetailVo(
        Long id,
        Long ownerId,
        String name,
        String summary,
        String category,
        String status,
        String latestVersion,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        AssetVersionVo latestVersionDetail
) {
}
