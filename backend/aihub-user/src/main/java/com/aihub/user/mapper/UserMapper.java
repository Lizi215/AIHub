package com.aihub.user.mapper;

import com.aihub.user.entity.UserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 单表 CRUD 走 BaseMapper，复杂 SQL 才落 Mapper XML。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
