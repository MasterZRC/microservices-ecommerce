package com.ecommerce.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.admin.entity.AdminOperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminOperationLogMapper extends BaseMapper<AdminOperationLog> {
}
