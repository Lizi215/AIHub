package com.aihub.user.service.impl;

import com.aihub.api.dto.UserBrief;
import com.aihub.common.auth.Role;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.user.entity.UserEntity;
import com.aihub.user.mapper.UserMapper;
import com.aihub.user.service.UserService;
import com.aihub.user.vo.UserVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户查询实现。
 */
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserEntity getActiveUser(Long userId) {
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getId, userId)
                .eq(UserEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    @Override
    @Transactional
    public String updateRole(Long targetUserId, String newRole, Long operatorId) {
        // 操作者必须是 Admin
        if (!Role.ADMIN.name().equals(com.aihub.common.trace.UserContext.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "only admin can change user roles");
        }
        Role role = parseRole(newRole);
        UserEntity user = getActiveUser(targetUserId);
        if (Role.ADMIN.name().equals(user.getRole()) && role != Role.ADMIN) {
            // 至少保留一个 Admin，全部降级会失去管理能力
            Long admins = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getRole, Role.ADMIN.name())
                    .eq(UserEntity::getDeleted, BaseEntity.NOT_DELETED));
            if (admins == null || admins <= 1) {
                throw new BizException(ErrorCode.FORBIDDEN, "cannot demote the last admin user");
            }
        }
        UserEntity update = new UserEntity();
        update.setId(targetUserId);
        update.setRole(role.name());
        userMapper.updateById(update);
        return role.name();
    }

    private Role parseRole(String newRole) {
        if (newRole == null || newRole.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "role is required");
        }
        try {
            return Role.valueOf(newRole.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "unknown role: " + newRole);
        }
    }

    @Override
    public UserVo toVo(UserEntity entity) {
        return new UserVo(entity.getId(), entity.getUsername(), entity.getNickname(), entity.getRole());
    }

    @Override
    public List<UserBrief> briefsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // 一次 IN 查询，避免按 id 逐个查
        List<UserEntity> users = userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getId, ids)
                .eq(UserEntity::getDeleted, BaseEntity.NOT_DELETED));
        return users.stream()
                .map(u -> new UserBrief(u.getId(), u.getNickname()))
                .toList();
    }
}
