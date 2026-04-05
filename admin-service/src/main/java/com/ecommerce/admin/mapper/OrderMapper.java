package com.ecommerce.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.admin.entity.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
