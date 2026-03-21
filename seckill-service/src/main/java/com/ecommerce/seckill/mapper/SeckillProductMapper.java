package com.ecommerce.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.seckill.entity.SeckillProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SeckillProductMapper extends BaseMapper<SeckillProduct> {

    /**
     * 批量更新秒杀商品库存（用于 Redis -> MySQL 定时同步）
     * @param ids 商品ID列表
     * @return 受影响的行数
     */
    int batchSyncStock(@Param("ids") List<Long> ids);

    /**
     * 批量查询进行中的秒杀商品（用于 Redis -> MySQL 定时同步）
     */
    @Select("SELECT * FROM seckill_product WHERE status = 1 AND start_time &lt;= NOW() AND end_time &gt;= NOW()")
    List<SeckillProduct> selectActiveProductsForSync();
}
