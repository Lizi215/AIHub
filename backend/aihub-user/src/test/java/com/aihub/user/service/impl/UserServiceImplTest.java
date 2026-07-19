package com.aihub.user.service.impl;

import com.aihub.common.auth.Role;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.user.entity.UserEntity;
import com.aihub.common.trace.UserContext;
import com.aihub.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户角色变更。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final long TARGET_ID = 200L;

    @Mock
    private UserMapper userMapper;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(userMapper);
        // 操作者必须是 Admin，service 从 ThreadLocal 读取角色
        UserContext.set(1L, Role.ADMIN.name());
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("Admin 可以提升用户为 Maintainer")
    void shouldUpdateRoleByAdmin() {
        UserEntity target = user(Role.USER.name());
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(target);

        String result = service.updateRole(TARGET_ID, "maintainer", 1L);

        assertThat(result).isEqualTo(Role.MAINTAINER.name());
        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.MAINTAINER.name());
    }

    @Test
    @DisplayName("非 Admin 变更角色被拒绝")
    void shouldRejectNonAdmin() {
        UserContext.set(2L, Role.USER.name());
        assertThatThrownBy(() -> service.updateRole(TARGET_ID, "MAINTAINER", 1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("非法角色被拒绝")
    void shouldRejectUnknownRole() {
        assertThatThrownBy(() -> service.updateRole(TARGET_ID, "GOD", 1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("最后一个 Admin 不能被降级（否则系统失去管理能力）")
    void shouldRejectDemotingLastAdmin() {
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user(Role.ADMIN.name()));
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.updateRole(TARGET_ID, "USER", 1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("存在多个 Admin 时允许降级其一")
    void shouldAllowDemotingWhenOtherAdminsExist() {
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user(Role.ADMIN.name()));
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(2L);

        service.updateRole(TARGET_ID, "USER", 1L);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.USER.name());
    }

    private UserEntity user(String role) {
        UserEntity user = new UserEntity();
        user.setId(TARGET_ID);
        user.setUsername("target");
        user.setRole(role);
        return user;
    }
}
