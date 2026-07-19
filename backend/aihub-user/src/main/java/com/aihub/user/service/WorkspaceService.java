package com.aihub.user.service;

import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.user.dto.CreateWorkspaceRequest;
import com.aihub.user.vo.WorkspaceVo;

/**
 * Workspace 服务。
 *
 * <p>AI 只生成草案，落库必须经用户确认，保存动作由用户在前端发起。
 */
public interface WorkspaceService {

    /** 创建，逐项校验资产存在且已发布，返回 workspaceId。 */
    Long create(Long userId, CreateWorkspaceRequest request);

    /** 我的 Workspace 列表，不含条目。 */
    PageResult<WorkspaceVo> list(Long userId, PageQuery query);

    /** 详情，含条目与资产可用性。 */
    WorkspaceVo detail(Long userId, Long id);

    /** 删除，软删除并连同条目。 */
    void delete(Long userId, Long id);
}
