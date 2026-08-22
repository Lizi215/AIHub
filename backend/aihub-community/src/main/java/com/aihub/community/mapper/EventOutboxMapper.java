package com.aihub.community.mapper;

import com.aihub.community.entity.EventOutboxEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventOutboxMapper extends BaseMapper<EventOutboxEntity> {
}
