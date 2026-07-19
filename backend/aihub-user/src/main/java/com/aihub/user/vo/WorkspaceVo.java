package com.aihub.user.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Workspace（对外）。
 */
public record WorkspaceVo(
        Long id,
        String name,
        String description,
        List<WorkspaceItemVo> items,
        LocalDateTime createTime
) {
}
