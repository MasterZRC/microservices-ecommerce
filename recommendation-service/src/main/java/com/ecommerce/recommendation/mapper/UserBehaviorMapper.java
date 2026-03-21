package com.ecommerce.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.recommendation.entity.UserBehavior;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserBehaviorMapper extends BaseMapper<UserBehavior> {
}