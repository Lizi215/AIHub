package com.aihub.asset.mapper;

import com.aihub.asset.entity.EventOutboxEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventOutboxMapper extends BaseMapper<EventOutboxEntity> {
}
