package com.aihub.user.service;

import com.aihub.api.dto.UserBrief;
import com.aihub.user.entity.UserEntity;
import com.aihub.user.vo.UserVo;

import java.util.List;

/**
 * 用户查询。
 */
public interface UserService {

    /**
     * 按 id 查询有效用户（未软删除）。
     *
     * @throws com.aihub.common.exception.BizException 用户不存在时
     */
    UserEntity getActiveUser(Long userId);

    /**
     * 变更用户角色（Admin only）。
     *
     * <p>角色随 JWT 签发，变更后需重新登录才生效。
     *
     * @return 变更后的角色名
     */
    String updateRole(Long targetUserId, String newRole, Long operatorId);

    UserVo toVo(UserEntity entity);

    /**
     * 批量查询用户简要信息（id → 昵称），供其他服务展示作者名。
     *
     * <p>查不到的 id 直接忽略，不抛异常，避免软删除用户让调用方的列表渲染失败。
     *
     * @param ids 用户 id 列表；空列表直接返回空结果，不查库
     */
    List<UserBrief> briefsByIds(List<Long> ids);
}
