package com.ecommerce.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.admin.entity.Category;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
